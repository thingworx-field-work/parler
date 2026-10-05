#!/usr/bin/env python3
"""Offline tests for context-compaction eval driver (stdlib unittest)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from context_compaction_judge import helper_stability_tuple, judge_turn
from run_context_compaction_eval import (
    CAPTURE_COVERAGE_OBSERVED_ONLY,
    build_helper_report_payload,
    expand_suite,
    node_driver_command,
    preflight_node_adapter,
    query_helper_report,
    run_node_adapter,
    validate_output_dir,
    wrap_helper_observation,
    write_jsonl,
)


REPO_ROOT = Path(__file__).resolve().parent.parent
DRIVER = Path(__file__).resolve().parent / "run_context_compaction_eval.py"
SUITE = Path(__file__).resolve().parent / "context_compaction" / "ten_turns.json"
GOLDEN = Path(__file__).resolve().parent / "context_compaction" / "ten_turns_golden.json"
CODEC_DIR = REPO_ROOT / "parler-ui" / "node_modules" / "@xudesheng" / "alwayson-js-codec"


class ContextCompactionEvalTest(unittest.TestCase):
    def test_expand_suite_has_ten_turns_and_golden(self) -> None:
        raw = json.loads(SUITE.read_text(encoding="utf-8"))
        plan = expand_suite(raw, SUITE)
        self.assertEqual(10, len(plan.expanded_turns))
        self.assertIn("turns", plan.golden)

    def test_output_dir_must_be_outside_repo_by_default(self) -> None:
        with self.assertRaises(ValueError):
            validate_output_dir(REPO_ROOT / "tmp-cc-out")

    def test_helper_stability_tuple_uses_usage_status_counters(self) -> None:
        report = {
            "callCount": 2,
            "knownCostUsd": "0.5000",
            "usageCompleteCount": 1,
            "usagePartialCount": 1,
            "usageUnavailableCount": 0,
            "usageInvalidCount": 0,
            "unknownCount": {"inputTokensTotal": 1},
        }
        self.assertEqual((2, "0.5000", 1, 1, 0, 0), helper_stability_tuple(report))

    def test_helper_stability_tuple_absent_fields_are_unknown(self) -> None:
        self.assertIsNone(helper_stability_tuple({"knownCostUsd": "0.1"}))

    def test_wrap_helper_observation_preserves_raw_report(self) -> None:
        raw = {"callCount": 1, "knownCostUsd": "0.1", "captureCoverage": "observed_only"}
        wrapped = wrap_helper_observation(raw)
        self.assertEqual(CAPTURE_COVERAGE_OBSERVED_ONLY, wrapped["captureCoverage"])
        self.assertFalse(wrapped["savingsEligible"])
        self.assertEqual(raw, wrapped["helperReport"])

    def test_judge_turn_requires_reference_evidence(self) -> None:
        verdict = judge_turn(
            {"id": 1},
            {"terminal": {"kind": "done"}, "wireEvents": []},
            {"terminal": "done", "requireChart": True},
        )
        self.assertEqual("insufficient_evidence", verdict["judgmentStatus"])

    def test_judge_turn_fails_numeric_chart_mismatch(self) -> None:
        golden = json.loads(GOLDEN.read_text(encoding="utf-8"))["turns"]["1"]
        verdict = judge_turn(
            {"id": 1},
            {
                "terminal": {"kind": "done"},
                "requestId": "r1",
                "conversationId": "c1",
                "wireEvents": [
                    {
                        "type": "chart",
                        "request_id": "r1",
                        "conversation_id": "c1",
                        "chart": {
                            "kind": "pie",
                            "source": golden["reference"]["chart"]["source"],
                            "series": [{"x": ["Running", "Idle", "Down"], "y": [600, 2, 1]}],
                        },
                    }
                ],
            },
            golden,
        )
        self.assertEqual("fail", verdict["judgmentStatus"])

    def test_fake_transport_runs_ten_turns_with_honest_automatic_yield(self) -> None:
        raw = json.loads(SUITE.read_text(encoding="utf-8"))
        plan = expand_suite(raw, SUITE)
        outcome = run_node_adapter(
            {
                "cmd": "run_suite",
                "fake": True,
                "turns": plan.expanded_turns,
                "golden": plan.golden,
                "clientWaitSeconds": 5,
            },
            fake=True,
        )
        turn_results = [e for e in outcome.events if e.get("kind") == "turn_result"]
        self.assertEqual(10, len(turn_results))
        pass_count = sum(1 for t in turn_results if t.get("judgmentStatus") == "pass")
        insufficient = [t for t in turn_results if t.get("judgmentStatus") == "insufficient_evidence"]
        self.assertEqual(8, pass_count)
        self.assertEqual(2, len(insufficient))
        self.assertEqual([6, 7], sorted(t.get("turnId") for t in insufficient))
        self.assertFalse(outcome.adapter_failed)

    def test_run_node_adapter_streams_events_incrementally(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            events_path = Path(tmp) / "events.jsonl"
            outcome = run_node_adapter(
                {
                    "cmd": "run_suite",
                    "fake": True,
                    "turns": [{"id": 1, "expandedPrompt": "one"}],
                    "golden": {"turns": {"1": json.loads(GOLDEN.read_text())["turns"]["1"]}},
                    "clientWaitSeconds": 2,
                },
                fake=True,
                events_path=events_path,
            )
            lines = events_path.read_text(encoding="utf-8").splitlines()
            self.assertGreaterEqual(len(lines), 2)
            self.assertFalse(outcome.adapter_failed)

    def test_preflight_reports_missing_codec_without_installing(self) -> None:
        if CODEC_DIR.is_dir():
            self.skipTest("codec already installed in parler-ui")
        with self.assertRaises(RuntimeError):
            preflight_node_adapter(require_codec=True)

    def test_package_entrypoint_imports_from_repo_root(self) -> None:
        env = os.environ.copy()
        env["PYTHONPATH"] = str(REPO_ROOT)
        proc = subprocess.run(
            [sys.executable, "-c", "import test_scripts.run_context_compaction_eval"],
            cwd=str(REPO_ROOT),
            env=env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, proc.returncode, proc.stderr or proc.stdout)

    def test_pep723_script_runs_plan_only_in_fresh_process(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "cc-run"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(DRIVER),
                    "--suite",
                    str(SUITE),
                    "--output-dir",
                    str(out),
                ],
                cwd=str(REPO_ROOT),
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, proc.returncode, proc.stderr or proc.stdout)
            run_doc = json.loads((out / "run.json").read_text(encoding="utf-8"))
            self.assertEqual("plan", run_doc["mode"])

    def test_codec_preflight_uses_live_loader_path(self) -> None:
        preflight = REPO_ROOT / "test_scripts" / "context_compaction" / "preflight_codec.mjs"
        proc = subprocess.run(
            node_driver_command(preflight),
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
        )
        if CODEC_DIR.is_dir():
            self.assertEqual(0, proc.returncode, proc.stderr or proc.stdout)
        else:
            self.assertNotEqual(0, proc.returncode)

    def test_node_driver_command_includes_wasm_modules(self) -> None:
        cmd = node_driver_command(REPO_ROOT / "test_scripts" / "context_compaction_alwayson.mjs")
        self.assertEqual(["node", "--experimental-wasm-modules", str(cmd[-1])], cmd)

    def test_write_jsonl_is_line_delimited(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            write_jsonl(path, [{"a": 1}, {"b": 2}])
            lines = path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(2, len(lines))
            self.assertEqual({"a": 1}, json.loads(lines[0]))

    def test_build_run_document_serializes_path_golden_ref(self) -> None:
        import run_context_compaction_eval as driver
        from argparse import Namespace

        raw = json.loads(SUITE.read_text(encoding="utf-8"))
        plan = expand_suite(raw, SUITE)
        manifest = Path("/tmp/user-golden.json")
        doc = driver.build_run_document(
            plan,
            Namespace(
                execute=True,
                fake_transport=False,
                output_dir=Path("/tmp/out"),
                golden_manifest=manifest,
                agent_thing="AgentA",
                gateway_thing="",
                helper_thing="AgentLlmUsageHelper",
            ),
            golden_path=manifest,
        )
        self.assertEqual(str(manifest), doc["goldenRef"])
        self.assertFalse(doc["pairing"]["pairable"])

    def test_run_node_adapter_stops_on_invoke_hang(self) -> None:
        outcome = run_node_adapter(
            {
                "cmd": "run_suite",
                "fake": True,
                "scenario": "invoke_hang",
                "turns": [{"id": 1, "expandedPrompt": "one"}],
                "golden": {"turns": {"1": json.loads(GOLDEN.read_text())["turns"]["1"]}},
                "clientWaitSeconds": 1,
                "invokeTimeoutSeconds": 1,
            },
            fake=True,
        )
        self.assertFalse(outcome.adapter_failed)
        turn_results = [e for e in outcome.events if e.get("kind") == "turn_result"]
        self.assertEqual(1, len(turn_results))
        self.assertEqual("completion_unknown", turn_results[0]["terminal"])

    def test_plan_only_writes_run_json_outside_repo(self) -> None:
        import run_context_compaction_eval as driver

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "cc-run"
            code = driver.main(["--suite", str(SUITE), "--output-dir", str(out)])
            self.assertEqual(0, code)
            run_doc = json.loads((out / "run.json").read_text(encoding="utf-8"))
            self.assertEqual("plan", run_doc["mode"])

    def test_build_helper_report_payload_omits_null_optional_filters(self) -> None:
        payload = build_helper_report_payload(
            start_time="2026-09-15T00:00:00Z",
            end_time="2026-09-15T23:59:59Z",
            conversation_id="SCPA_Agent_Sonnet_ID",
            agent_thing="SCPA_Agent_Sonnet",
        )
        self.assertEqual(
            {
                "StartTime": "2026-09-15T00:00:00Z",
                "EndTime": "2026-09-15T23:59:59Z",
                "conversationId": "SCPA_Agent_Sonnet_ID",
                "agentThing": "SCPA_Agent_Sonnet",
            },
            payload,
        )
        self.assertNotIn("model", payload)

    def test_query_helper_report_posts_payload_without_model_null(self) -> None:
        captured: dict[str, Any] = {}

        class FakeResponse:
            def read(self) -> bytes:
                return b'{"callCount": 1, "knownCostUsd": "0.1"}'

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def fake_urlopen(req, timeout=120):
            captured["body"] = json.loads(req.data.decode("utf-8"))
            captured["url"] = req.full_url
            return FakeResponse()

        with patch("urllib.request.urlopen", fake_urlopen):
            report = query_helper_report(
                server="https://lab.example:8443",
                app_key="k",
                helper_thing="AgentLlmUsageCalculator",
                agent_thing="SCPA_Agent_Sonnet",
                conversation_id="SCPA_Agent_Sonnet_ID",
                start_time="2026-09-15T00:00:00Z",
                end_time="2026-09-15T23:59:59Z",
            )
        self.assertNotIn("model", captured["body"])
        self.assertEqual("SCPA_Agent_Sonnet_ID", captured["body"]["conversationId"])
        self.assertEqual("SCPA_Agent_Sonnet", captured["body"]["agentThing"])
        self.assertIn(
            "/Thingworx/Things/AgentLlmUsageCalculator/Services/GetLlmUsageReport",
            captured["url"],
        )
        self.assertEqual(1, report["callCount"])


if __name__ == "__main__":
    unittest.main()
