#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""M1a user-launched ten-turn driver (plan-only by default; --execute for live)."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from test_scripts.context_compaction_judge import helper_stability_tuple
except ModuleNotFoundError:  # PEP 723 direct script execution adds test_scripts/ to sys.path
    from context_compaction_judge import helper_stability_tuple

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
PARLER_UI_DIR = REPO_ROOT / "parler-ui"
DEFAULT_SUITE = SCRIPT_DIR / "context_compaction" / "ten_turns.json"
DEFAULT_GOLDEN = SCRIPT_DIR / "context_compaction" / "ten_turns_golden.json"
NODE_ADAPTER = SCRIPT_DIR / "context_compaction_alwayson.mjs"
CODEC_PREFLIGHT = SCRIPT_DIR / "context_compaction" / "preflight_codec.mjs"
NODE_WASM_FLAG = "--experimental-wasm-modules"


def node_driver_command(script: Path) -> list[str]:
    """Launch Node with the runtime flags required by @xudesheng/alwayson-js-codec."""
    return ["node", NODE_WASM_FLAG, str(script)]

CAPTURE_COVERAGE_OBSERVED_ONLY = "observed_only"
ADAPTER_SHUTDOWN_TIMEOUT_S = 30


@dataclass(frozen=True)
class NodeAdapterOutcome:
    events: list[dict[str, Any]]
    exit_code: int
    stderr: str
    adapter_failed: bool
    failure_reason: str | None = None


@dataclass(frozen=True)
class SuitePlan:
    raw: dict[str, Any]
    expanded_turns: list[dict[str, Any]]
    golden: dict[str, Any]


def load_dotenv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, rest = line.partition("=")
        val = rest.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
            val = val[1:-1]
        out[key.strip()] = val
    return out


def load_golden(raw: dict[str, Any], suite_path: Path) -> dict[str, Any]:
    ref = raw.get("goldenRef")
    golden_path = (suite_path.parent / ref) if ref else DEFAULT_GOLDEN
    if not golden_path.is_file():
        return {}
    return json.loads(golden_path.read_text(encoding="utf-8"))


def expand_suite(raw: dict[str, Any], suite_path: Path) -> SuitePlan:
    variables = dict(raw.get("variables") or {})
    turns: list[dict[str, Any]] = []
    for turn in raw.get("turns") or []:
        if not isinstance(turn, dict):
            raise ValueError("each turn must be an object")
        prompt = str(turn.get("prompt") or "")
        for key, value in variables.items():
            prompt = prompt.replace("{{" + key + "}}", str(value))
        item = dict(turn)
        item["expandedPrompt"] = prompt
        turns.append(item)
    if len(turns) != 10:
        raise ValueError(f"suite must contain exactly 10 turns, found {len(turns)}")
    return SuitePlan(raw=raw, expanded_turns=turns, golden=load_golden(raw, suite_path))


def wrap_helper_observation(report: dict[str, Any]) -> dict[str, Any]:
    return {
        "helperReport": report,
        "captureCoverage": CAPTURE_COVERAGE_OBSERVED_ONLY,
        "savingsEligible": False,
    }


def capture_helper_observations(
    *,
    server: str,
    app_key: str,
    helper_thing: str,
    agent_thing: str,
    conversation_id: str,
    start_time: str,
    end_time: str,
    interval_seconds: int,
    reads: int,
) -> dict[str, Any]:
    observations: list[dict[str, Any]] = []
    query_error: str | None = None
    for index in range(reads):
        if index > 0:
            time.sleep(max(0, interval_seconds))
        try:
            report = query_helper_report(
                server=server,
                app_key=app_key,
                helper_thing=helper_thing,
                agent_thing=agent_thing,
                conversation_id=conversation_id,
                start_time=start_time,
                end_time=end_time,
            )
            observations.append(
                {
                    "readIndex": index + 1,
                    "clientObservedAt": datetime.now(timezone.utc).isoformat(),
                    "reportAsOf": report.get("reportAsOf"),
                    **wrap_helper_observation(report),
                }
            )
        except Exception as exc:  # noqa: BLE001 - preserve partial observations
            query_error = str(exc)
            break
    stable = False
    stable_tuple: tuple[Any, ...] | None = None
    if len(observations) >= 2:
        first_tuple = helper_stability_tuple(observations[0]["helperReport"])
        last_tuple = helper_stability_tuple(observations[-1]["helperReport"])
        if first_tuple is not None and first_tuple == last_tuple:
            stable = True
            stable_tuple = first_tuple
    return {
        "captureCoverage": CAPTURE_COVERAGE_OBSERVED_ONLY,
        "savingsEligible": False,
        "intervalSeconds": interval_seconds,
        "reads": reads,
        "observations": observations,
        "stableObservedLowerBound": stable and stable_tuple is not None and stable_tuple[0] > 0,
        "queryError": query_error,
        "note": "Two matching observations are stability evidence for the observed lower bound, not final cost.",
    }


def build_helper_report_payload(
    *,
    start_time: str,
    end_time: str,
    conversation_id: str = "",
    agent_thing: str = "",
    model: str | None = None,
) -> dict[str, Any]:
    """Build GetLlmUsageReport params; omit unset optional STRING filters."""
    payload: dict[str, Any] = {
        "StartTime": start_time,
        "EndTime": end_time,
    }
    if conversation_id:
        payload["conversationId"] = conversation_id
    if agent_thing:
        payload["agentThing"] = agent_thing
    if model:
        payload["model"] = model
    return payload


def query_helper_report(
    *,
    server: str,
    app_key: str,
    helper_thing: str,
    agent_thing: str,
    conversation_id: str,
    start_time: str,
    end_time: str,
) -> dict[str, Any]:
    payload = build_helper_report_payload(
        start_time=start_time,
        end_time=end_time,
        conversation_id=conversation_id,
        agent_thing=agent_thing,
    )
    body = invoke_thing_service(server, app_key, helper_thing, "GetLlmUsageReport", payload)
    if isinstance(body, str):
        return json.loads(body)
    if isinstance(body, dict):
        return body
    raise ValueError("unexpected helper response type")


def invoke_thing_service(server: str, app_key: str, thing: str, service: str, params: dict[str, Any]) -> Any:
    url = f"{server.rstrip('/')}/Thingworx/Things/{thing}/Services/{service}"
    req = urllib.request.Request(
        url,
        data=json.dumps(params).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "appKey": app_key,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read().decode("utf-8")
    parsed = json.loads(raw)
    if isinstance(parsed, dict) and "rows" in parsed and parsed["rows"]:
        row = parsed["rows"][0]
        if isinstance(row, dict) and "result" in row:
            result = row["result"]
            if isinstance(result, str):
                try:
                    return json.loads(result)
                except json.JSONDecodeError:
                    return result
            return result
    return parsed


def preflight_node_adapter(*, require_codec: bool) -> None:
    if not require_codec:
        return
    proc = subprocess.run(
        node_driver_command(CODEC_PREFLIGHT),
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "codec preflight failed").strip()
        if "Cannot find module '@xudesheng/alwayson-js-codec'" in detail:
            raise RuntimeError(
                "Node AlwaysOn codec package missing under parler-ui/ "
                f"(run npm install in parler-ui before --execute): {detail}"
            )
        raise RuntimeError(f"Node AlwaysOn codec preflight failed: {detail}")


def sha256_file(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def adapter_timeout_seconds(command: dict[str, Any], shutdown_timeout_s: int) -> int:
    explicit = int(command.get("adapterTimeoutSeconds") or 0)
    if explicit > 0:
        return explicit
    turns = command.get("turns") or []
    client_wait = int(command.get("clientWaitSeconds") or 600)
    turn_gap = int(command.get("turnGapSeconds") or 0)
    per_turn = client_wait + max(0, turn_gap)
    return max(shutdown_timeout_s, per_turn * max(1, len(turns)) + shutdown_timeout_s)


def run_node_adapter(
    command: dict[str, Any],
    *,
    fake: bool,
    events_path: Path | None = None,
    turns_path: Path | None = None,
    shutdown_timeout_s: int = ADAPTER_SHUTDOWN_TIMEOUT_S,
) -> NodeAdapterOutcome:
    env = {**os.environ}
    if fake:
        env["PARLER_CC_FAKE"] = "1"
    proc = subprocess.Popen(
        node_driver_command(NODE_ADAPTER),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        cwd=str(REPO_ROOT),
        env=env,
    )
    assert proc.stdin is not None and proc.stdout is not None and proc.stderr is not None
    proc.stdin.write(json.dumps(command) + "\n")
    proc.stdin.close()

    events: list[dict[str, Any]] = []
    turn_results: list[dict[str, Any]] = []
    failure_reason: str | None = None
    events_handle = events_path.open("w", encoding="utf-8") if events_path else None
    stderr_lines: list[str] = []
    stdout_queue: queue.Queue[str | None] = queue.Queue()
    stdout_done = threading.Event()
    deadline = time.monotonic() + adapter_timeout_seconds(command, shutdown_timeout_s)

    def read_stderr() -> None:
        for line in proc.stderr:
            stderr_lines.append(line)

    def read_stdout() -> None:
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                stdout_queue.put(line)
        finally:
            stdout_done.set()

    stderr_thread = threading.Thread(target=read_stderr, daemon=True)
    stdout_thread = threading.Thread(target=read_stdout, daemon=True)
    stderr_thread.start()
    stdout_thread.start()

    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                failure_reason = failure_reason or "adapter_timeout"
                proc.kill()
                break
            try:
                line = stdout_queue.get(timeout=min(0.5, remaining))
            except queue.Empty:
                if proc.poll() is not None and stdout_done.is_set() and stdout_queue.empty():
                    break
                continue
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError as exc:
                failure_reason = f"malformed_adapter_output:{exc.msg}"
                break
            events.append(event)
            if events_handle is not None:
                events_handle.write(json.dumps(event, sort_keys=True) + "\n")
                events_handle.flush()
            if event.get("kind") == "turn_result":
                turn_results.append(event)
                if turns_path is not None:
                    write_json(turns_path, {"turns": turn_results})
            if event.get("kind") == "run_complete":
                break
    finally:
        if events_handle is not None:
            events_handle.close()

    try:
        exit_code = proc.wait(timeout=shutdown_timeout_s)
    except subprocess.TimeoutExpired:
        proc.kill()
        exit_code = proc.wait(timeout=5)
        failure_reason = failure_reason or "adapter_shutdown_timeout"

    stderr_thread.join(timeout=1)
    stdout_thread.join(timeout=1)
    stderr = "".join(stderr_lines)
    adapter_failed = exit_code != 0 or failure_reason is not None
    if exit_code != 0 and failure_reason is None:
        failure_reason = f"node_adapter_exit_{exit_code}"
    return NodeAdapterOutcome(
        events=events,
        exit_code=exit_code,
        stderr=stderr,
        adapter_failed=adapter_failed,
        failure_reason=failure_reason,
    )


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=True) + "\n")


def validate_output_dir(path: Path) -> None:
    repo_resolved = REPO_ROOT.resolve()
    resolved = path.resolve()
    if resolved == repo_resolved or repo_resolved in resolved.parents:
        raise ValueError(
            "output directory must be outside the repository unless explicitly gitignored; "
            f"refusing {resolved}"
        )


def json_safe_ref(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, Path):
        return str(value)
    return str(value)


def build_run_document(
    plan: SuitePlan,
    args: argparse.Namespace,
    *,
    golden_path: Path | None,
    outcome: NodeAdapterOutcome | None = None,
) -> dict[str, Any]:
    golden_ref = (
        json_safe_ref(args.golden_manifest)
        if args.execute
        else (plan.raw.get("goldenRef") or "ten_turns_golden.json")
    )
    doc = {
        "suite": plan.raw.get("suite"),
        "version": plan.raw.get("version"),
        "mode": "execute" if args.execute else ("fake" if args.fake_transport else "plan"),
        "outputDir": str(args.output_dir),
        "turnCount": len(plan.expanded_turns),
        "helperCapture": plan.raw.get("helperCapture"),
        "clientWaitSeconds": plan.raw.get("clientWaitSeconds"),
        "turnGapSeconds": plan.raw.get("turnGapSeconds", 0),
        "variables": plan.raw.get("variables") or {},
        "datasetManifest": plan.raw.get("datasetManifest"),
        "goldenRef": golden_ref,
        "goldenSha256": sha256_file(golden_path),
        "turns": plan.expanded_turns,
        "pairing": {
            "agentThing": args.agent_thing or None,
            "gatewayThingOverride": args.gateway_thing or None,
            "helperThing": args.helper_thing,
            "pairable": False,
            "notes": ["pairing requires User lab AgentSettings/Provider configuration manifest; not verified by driver"],
        },
    }
    if args.execute:
        doc["pairing"]["requiresUserLabConfig"] = True
        if not args.agent_thing:
            doc["pairing"]["notes"].append("missing agentThing")
        else:
            doc["pairing"]["notes"].append("agentThing supplied but lab configuration not attested")
    if outcome is not None:
        doc["adapter"] = {
            "exitCode": outcome.exit_code,
            "adapterFailed": outcome.adapter_failed,
            "failureReason": outcome.failure_reason,
        }
    return doc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=DEFAULT_SUITE)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument("--execute", action="store_true", help="Send live requests (User-operated lab only).")
    parser.add_argument("--fake-transport", action="store_true", help="Offline Node fake transport (tests/local).")
    parser.add_argument("--agent-thing", default="")
    parser.add_argument(
        "--gateway-thing",
        default="",
        help="Optional existing Gateway/conversationId override for --execute; otherwise GetOrCreateConversationId is used.",
    )
    parser.add_argument(
        "--golden-manifest",
        type=Path,
        default=None,
        help="User-supplied reference golden manifest for --execute (required for task judgments).",
    )
    parser.add_argument("--helper-thing", default="AgentLlmUsageHelper")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    validate_output_dir(args.output_dir)
    raw = json.loads(args.suite.read_text(encoding="utf-8"))
    plan = expand_suite(raw, args.suite)

    golden_path: Path | None = None
    if args.fake_transport:
        golden_path = (args.suite.parent / plan.raw.get("goldenRef", "ten_turns_golden.json"))
    elif args.execute and args.golden_manifest is not None:
        golden_path = args.golden_manifest

    write_json(
        args.output_dir / "run.json",
        build_run_document(plan, args, golden_path=golden_path),
    )

    if not args.execute and not args.fake_transport:
        print(json.dumps({"status": "plan_only", "turnCount": len(plan.expanded_turns)}, indent=2))
        return 0

    golden = plan.golden if args.fake_transport else {}
    if args.execute:
        if args.golden_manifest is None:
            print("--golden-manifest is required with --execute for reference task judgments", file=sys.stderr)
            return 2
        golden = json.loads(args.golden_manifest.read_text(encoding="utf-8"))
        try:
            preflight_node_adapter(require_codec=True)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 2

    command: dict[str, Any] = {
        "cmd": "run_suite",
        "turns": plan.expanded_turns,
        "golden": golden,
        "clientWaitSeconds": plan.raw.get("clientWaitSeconds", 600),
        "turnGapSeconds": plan.raw.get("turnGapSeconds", 0),
        "suiteConfig": {
            "suite": plan.raw.get("suite"),
            "variables": plan.raw.get("variables") or {},
            "datasetManifest": plan.raw.get("datasetManifest"),
            "goldenRef": str(golden_path) if golden_path else None,
            "goldenSha256": sha256_file(golden_path),
            "turnGapSeconds": plan.raw.get("turnGapSeconds", 0),
        },
    }

    events_path = args.output_dir / "events.jsonl"
    turns_path = args.output_dir / "turns.json"

    if args.execute:
        env = load_dotenv(args.env_file)
        server = env.get("DEV_SERVER", "")
        app_key = env.get("DEV_KEY", "")
        if not server or not app_key:
            print("DEV_SERVER and DEV_KEY required in --env-file for --execute", file=sys.stderr)
            return 2
        if not args.agent_thing:
            print("--agent-thing is required with --execute", file=sys.stderr)
            return 2
        variables = plan.raw.get("variables") or {}
        command.update(
            {
                "fake": False,
                "agentThing": args.agent_thing,
                "credentials": {"server": server, "appKey": app_key},
                "userTimezone": variables.get("userTimezone", "America/New_York"),
                "hostContext": variables.get("hostContext", ""),
            }
        )
        if args.gateway_thing:
            command["conversationId"] = args.gateway_thing
        started = datetime.now(timezone.utc).isoformat()
        outcome = run_node_adapter(command, fake=False, events_path=events_path, turns_path=turns_path)
        write_json(
            args.output_dir / "run.json",
            build_run_document(plan, args, golden_path=golden_path, outcome=outcome),
        )
        if not turns_path.exists():
            write_json(
                turns_path,
                {"turns": [e for e in outcome.events if e.get("kind") == "turn_result"]},
            )
        if outcome.adapter_failed:
            write_json(
                args.output_dir / "usage-report.json",
                {
                    "captureCoverage": CAPTURE_COVERAGE_OBSERVED_ONLY,
                    "savingsEligible": False,
                    "observations": [],
                    "skipped": True,
                    "reason": outcome.failure_reason or "adapter_failed",
                },
            )
            print(outcome.stderr or outcome.failure_reason or "adapter failed", file=sys.stderr)
            return 1
        ended = datetime.now(timezone.utc).isoformat()
        conversation_id = next(
            (
                e.get("conversationId")
                for e in outcome.events
                if e.get("kind") in {"run_identity", "turn_result", "run_complete"} and e.get("conversationId")
            ),
            "",
        )
        if not conversation_id:
            write_json(
                args.output_dir / "usage-report.json",
                {
                    "captureCoverage": CAPTURE_COVERAGE_OBSERVED_ONLY,
                    "savingsEligible": False,
                    "observations": [],
                    "skipped": True,
                    "reason": "missing_conversation_identity",
                },
            )
            return 1
        usage = capture_helper_observations(
            server=server,
            app_key=app_key,
            helper_thing=args.helper_thing,
            agent_thing=args.agent_thing,
            conversation_id=conversation_id,
            start_time=started,
            end_time=ended,
            interval_seconds=int((plan.raw.get("helperCapture") or {}).get("intervalSeconds") or 30),
            reads=int((plan.raw.get("helperCapture") or {}).get("reads") or 2),
        )
        write_json(args.output_dir / "usage-report.json", usage)
        return 0

    command["fake"] = True
    outcome = run_node_adapter(command, fake=True, events_path=events_path, turns_path=turns_path)
    write_json(
        args.output_dir / "run.json",
        build_run_document(plan, args, golden_path=golden_path, outcome=outcome),
    )
    if not turns_path.exists():
        write_json(
            turns_path,
            {"turns": [e for e in outcome.events if e.get("kind") == "turn_result"]},
        )
    write_json(
        args.output_dir / "usage-report.json",
        {
            "captureCoverage": CAPTURE_COVERAGE_OBSERVED_ONLY,
            "savingsEligible": False,
            "observations": [],
            "note": "fake transport run — no helper queries",
        },
    )
    return 1 if outcome.adapter_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
