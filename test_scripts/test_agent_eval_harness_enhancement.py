"""Unit tests for harness-enhancement runner behavior (no live ThingWorx)."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from test_scripts.agent_eval import (
    EXIT_INFRA_FAIL,
    EXIT_OK,
    InfraHttpError,
    build_turn_context,
    case_skip_for_fixtures,
    classify_provider_failure_kind,
    count_report_statuses,
    eval_assertion_item,
    expand_suite_assertion_groups,
    flush_partial_report,
    main,
    parse_agent_matrix_entry,
    post_chat_incomplete_trace_row,
    pre_chat_stream_query,
    resolve_agent_matrix,
    resolve_env_interpolation,
    resolve_suite_fixtures,
    rollup_case_failure_kind,
    CONTEXT_CHECKPOINT_ROLE,
    trace_complete_strict,
    validate_case_skip_unless_env,
    validate_suite,
    write_report_files,
)


class TestEnvInterpolation(unittest.TestCase):
    def test_whole_string_env(self) -> None:
        import os

        os.environ["AGENT_EVAL_TEST_THING"] = "MyThing"
        try:
            v, ok = resolve_env_interpolation("${AGENT_EVAL_TEST_THING}")
            self.assertTrue(ok)
            self.assertEqual(v, "MyThing")
        finally:
            del os.environ["AGENT_EVAL_TEST_THING"]

    def test_missing_env(self) -> None:
        v, ok = resolve_env_interpolation("${AGENT_EVAL_DEFINITELY_MISSING_XYZ}")
        self.assertFalse(ok)
        self.assertIsNone(v)


class TestAssertionGroups(unittest.TestCase):
    def test_expand_group_in_assertions(self) -> None:
        suite = {
            "version": 1,
            "suite": "t",
            "agentMatrix": {"gpt": "Agent"},
            "assertionGroups": {
                "grp": [{"finalContains": "OK"}],
            },
            "cases": [
                {
                    "id": "c1",
                    "turns": [
                        {
                            "user": "hi",
                            "acceptableOutcomes": [
                                {
                                    "name": "ok",
                                    "score": 1.0,
                                    "assertions": [
                                        {"useAssertionGroup": "grp"},
                                        {"toolCalled": "invoke_service"},
                                    ],
                                }
                            ],
                        }
                    ],
                }
            ],
        }
        expand_suite_assertion_groups(suite, Path("suite.yaml"))
        asserts = suite["cases"][0]["turns"][0]["acceptableOutcomes"][0]["assertions"]
        self.assertEqual(len(asserts), 2)
        self.assertEqual(asserts[0], {"finalContains": "OK"})

    def test_unknown_group_errors(self) -> None:
        suite = {
            "version": 1,
            "suite": "t",
            "agentMatrix": {"gpt": "Agent"},
            "assertionGroups": {},
            "cases": [
                {
                    "id": "c1",
                    "turns": [
                        {
                            "user": "hi",
                            "acceptableOutcomes": [
                                {
                                    "name": "ok",
                                    "score": 1.0,
                                    "assertions": [{"useAssertionGroup": "missing"}],
                                }
                            ],
                        }
                    ],
                }
            ],
        }
        with self.assertRaises(SystemExit):
            expand_suite_assertion_groups(suite, Path("suite.yaml"))


class TestPerToolCounters(unittest.TestCase):
    def test_tool_called_times(self) -> None:
        ctx = build_turn_context("done", [])
        ok, _ = eval_assertion_item(
            {"toolCalledTimesAtLeast": {"tool": "start_playbook", "count": 1}},
            ctx,
            ordered_tool_names=["start_playbook", "query_alert_summary"],
            tool_calls_flat=[],
            tool_result_texts=[],
            assistant_row_count=1,
        )
        self.assertTrue(ok)
        ok2, _ = eval_assertion_item(
            {"toolCalledTimesAtMost": {"tool": "query_property_history", "count": 0}},
            ctx,
            ordered_tool_names=["start_playbook"],
            tool_calls_flat=[],
            tool_result_texts=[],
            assistant_row_count=1,
        )
        self.assertTrue(ok2)


class TestProviderFailureKind(unittest.TestCase):
    def test_classifies_rate_limit(self) -> None:
        exc = InfraHttpError(429, "Too Many Requests", "http://x", "rate limit exceeded")
        self.assertEqual(classify_provider_failure_kind(exc, phase="chat"), "provider_error")

    def test_non_chat_phase(self) -> None:
        exc = InfraHttpError(429, "Too Many Requests", "http://x", "rate limit")
        self.assertIsNone(classify_provider_failure_kind(exc, phase="stream_query"))


class TestCaseRollup(unittest.TestCase):
    def test_reject_precedence(self) -> None:
        turns = [{"rejectHits": ["rejectIf:finalContains:x"], "score": 0.0}]
        fk = rollup_case_failure_kind(turns, status="fail", case_pass=False)
        self.assertEqual(fk, "assertion_failed")

    def test_semantic_fail(self) -> None:
        turns = [{"score": 0.0, "rejectHits": []}]
        fk = rollup_case_failure_kind(turns, status="fail", case_pass=False)
        self.assertEqual(fk, "semantic_failed")


class TestPartialReportRoundTrip(unittest.TestCase):
    def test_interrupted_partial_writes(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td)
            report = {
                "meta": {
                    "suite": "t",
                    "runStatus": "interrupted",
                    "wallMs": 123.0,
                    "statusCounts": {"ok": 1, "interrupted": 1},
                },
                "results": [
                    {"kind": "case", "status": "ok", "caseId": "a"},
                    {
                        "kind": "case",
                        "status": "interrupted",
                        "caseId": "b",
                        "phase": "chat",
                        "error": "interrupted_during_chat",
                    },
                ],
            }
            flush_partial_report(out, report)
            partial = json.loads((out / "report.partial.json").read_text(encoding="utf-8"))
            self.assertEqual(partial["meta"]["runStatus"], "interrupted")
            self.assertEqual(count_report_statuses(partial["results"])["interrupted"], 1)
            self.assertTrue((out / "model-comparison.partial.md").is_file())


class TestMainTurnPathHelpers(unittest.TestCase):
    def test_pre_chat_success_does_not_reference_error_row(self) -> None:
        rows = [{"role": "assistant", "content": "hi", "toolCalls": ""}]
        with mock.patch("test_scripts.agent_eval.query_stream_data", return_value=rows):
            got_rows, err = pre_chat_stream_query(
                "http://x",
                "key",
                conversation_id="conv",
                turn_index=0,
                timeout_s=30.0,
            )
        self.assertIsNone(err)
        self.assertEqual(got_rows, rows)

    def test_pre_chat_failure_returns_infra_row(self) -> None:
        exc = InfraHttpError(500, "err", "http://x", "body")
        with mock.patch("test_scripts.agent_eval.query_stream_data", side_effect=exc):
            got_rows, err = pre_chat_stream_query(
                "http://x",
                "key",
                conversation_id="conv",
                turn_index=0,
                timeout_s=30.0,
            )
        self.assertIsNone(got_rows)
        self.assertIsNotNone(err)
        assert err is not None
        self.assertEqual(err["code"], "stream_query_before_chat")

    def test_post_chat_complete_trace_allows_assertions(self) -> None:
        final = "hello"
        delta = [{"role": "assistant", "content": final, "toolCalls": ""}]
        self.assertTrue(trace_complete_strict(delta, final))
        row = post_chat_incomplete_trace_row(
            turn_index=0,
            delta=delta,
            final_text=final,
            buf_wait_s=0.0,
            chat_ms=1.0,
            buffer_wait_ms=0.0,
            stream_query_ms=2.0,
        )
        self.assertIsNone(row)

    def test_trailing_context_checkpoint_rows_do_not_break_completeness(self):
        """§9.1: the checkpoint row is appended after the final assistant by design."""
        final = "the answer"
        delta = [
            {"role": "user", "content": "q", "toolCalls": ""},
            {"role": "assistant", "content": final, "toolCalls": ""},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
        ]
        self.assertTrue(trace_complete_strict(delta, final))

    def test_several_trailing_checkpoint_rows_are_tolerated(self):
        final = "the answer"
        delta = [
            {"role": "assistant", "content": final, "toolCalls": ""},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
        ]
        self.assertTrue(trace_complete_strict(delta, final))

    def test_other_unknown_trailing_roles_still_fail_closed(self):
        """The tolerance is one named role, not "skip anything I do not recognize"."""
        final = "the answer"
        for role in ("ui_feedback", "future_internal_role", "tool"):
            delta = [
                {"role": "assistant", "content": final, "toolCalls": ""},
                {"role": role, "content": "x", "toolCalls": ""},
            ]
            self.assertFalse(trace_complete_strict(delta, final), role)

    def test_a_checkpoint_does_not_substitute_for_a_missing_final_assistant(self):
        """Skipping trailing checkpoints must not weaken the identity or content check beneath them."""
        delta = [
            {"role": "user", "content": "q", "toolCalls": ""},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
        ]
        self.assertFalse(trace_complete_strict(delta, "the answer"))

        mismatched = [
            {"role": "assistant", "content": "a different answer", "toolCalls": ""},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
        ]
        self.assertFalse(trace_complete_strict(mismatched, "the answer"))

        tool_calling = [
            {"role": "assistant", "content": "", "toolCalls": '[{"id": "tc-1"}]'},
            {"role": CONTEXT_CHECKPOINT_ROLE, "content": "{}", "toolCalls": ""},
        ]
        self.assertFalse(trace_complete_strict(tool_calling, "the answer"))

    def test_checkpoint_rows_never_enter_usage_totals(self):
        """§9.1: empty llmUsageJson and zero token columns must not create a round or move totals."""
        from test_scripts.agent_eval import aggregate_llm_usage_from_delta

        assistant = {
            "role": "assistant",
            "llmUsageJson": json.dumps({"promptTokens": 11, "completionTokens": 7}),
            "promptTokens": 11,
            "completionTokens": 7,
        }
        checkpoint = {
            "role": CONTEXT_CHECKPOINT_ROLE,
            "llmUsageJson": "",
            "promptTokens": 0,
            "completionTokens": 0,
        }
        self.assertEqual(
            aggregate_llm_usage_from_delta([assistant]),
            aggregate_llm_usage_from_delta([assistant, checkpoint]),
        )

    def test_post_chat_incomplete_trace_returns_infra_row(self) -> None:
        row = post_chat_incomplete_trace_row(
            turn_index=0,
            delta=[],
            final_text="hello",
            buf_wait_s=0.0,
            chat_ms=1.0,
            buffer_wait_ms=0.0,
            stream_query_ms=2.0,
        )
        self.assertIsNotNone(row)
        assert row is not None
        self.assertEqual(row["code"], "stream_rows_not_visible_after_chat")


class TestSkipUnlessEnv(unittest.TestCase):
    def test_list_requires_all_env_vars(self) -> None:
        case = {
            "skipUnlessEnv": ["AGENT_EVAL_A", "AGENT_EVAL_B"],
            "skipReason": "both_missing",
        }
        os.environ["AGENT_EVAL_A"] = "set"
        os.environ.pop("AGENT_EVAL_B", None)
        try:
            skip, reason = case_skip_for_fixtures(case, {})
            self.assertTrue(skip)
            self.assertEqual(reason, "both_missing")
        finally:
            os.environ.pop("AGENT_EVAL_A", None)

    def test_list_passes_when_all_set(self) -> None:
        case = {
            "skipUnlessEnv": ["AGENT_EVAL_A", "AGENT_EVAL_B"],
        }
        os.environ["AGENT_EVAL_A"] = "1"
        os.environ["AGENT_EVAL_B"] = "2"
        try:
            skip, _ = case_skip_for_fixtures(case, {})
            self.assertFalse(skip)
        finally:
            os.environ.pop("AGENT_EVAL_A", None)
            os.environ.pop("AGENT_EVAL_B", None)


class TestEvidenceGroundingToolResultRegex(unittest.TestCase):
    """Guards YAML toolResultRegex literals against observed live false negatives."""

    def test_total_count_regex_matches_taxonomy_tool_json(self) -> None:
        import re

        from pathlib import Path

        import yaml

        suite_path = Path(__file__).resolve().parents[1] / "docs/agent/evals/evidence_grounding.yaml"
        suite = yaml.safe_load(suite_path.read_text(encoding="utf-8"))
        case = next(c for c in suite["cases"] if c["id"] == "taxonomy_zero_count_not_missing_entity")
        pat = None
        for outcome in case["turns"][0]["acceptableOutcomes"]:
            if outcome["name"] == "zero_count_with_tool_evidence":
                for item in outcome["assertions"]:
                    if isinstance(item, dict) and "toolResultRegex" in item:
                        pat = item["toolResultRegex"]
        self.assertIsNotNone(pat)
        sample = (
            '{"status":"success","totalCount":0,'
            '"resultKind":"ENTITY_TAXONOMY_QUERY_EMPTY","rootEntityList":[]}'
        )
        assert pat is not None
        self.assertIsNotNone(re.search(pat, sample), f"pattern {pat!r} did not match sample JSON")


class TestValidateSkipUnlessEnv(unittest.TestCase):
    def test_rejects_empty_string(self) -> None:
        with self.assertRaises(SystemExit) as ctx:
            validate_case_skip_unless_env(
                {"id": "c1", "skipUnlessEnv": "   "},
                Path("suite.yaml"),
            )
        self.assertIn("non-empty", str(ctx.exception))

    def test_rejects_empty_list(self) -> None:
        with self.assertRaises(SystemExit) as ctx:
            validate_case_skip_unless_env(
                {"id": "c1", "skipUnlessEnv": []},
                Path("suite.yaml"),
            )
        self.assertIn("list must be non-empty", str(ctx.exception))

    def test_rejects_non_string_list_item(self) -> None:
        with self.assertRaises(SystemExit) as ctx:
            validate_case_skip_unless_env(
                {"id": "c1", "skipUnlessEnv": ["OK", 42]},
                Path("suite.yaml"),
            )
        self.assertIn("skipUnlessEnv[1]", str(ctx.exception))

    def test_rejects_scalar_number(self) -> None:
        with self.assertRaises(SystemExit) as ctx:
            validate_case_skip_unless_env(
                {"id": "c1", "skipUnlessEnv": 42},
                Path("suite.yaml"),
            )
        self.assertIn("string or a non-empty list", str(ctx.exception))

    def test_accepts_string_and_list(self) -> None:
        validate_case_skip_unless_env(
            {"id": "c1", "skipUnlessEnv": "AGENT_EVAL_A"},
            Path("suite.yaml"),
        )
        validate_case_skip_unless_env(
            {"id": "c1", "skipUnlessEnv": ["AGENT_EVAL_A", "AGENT_EVAL_B"]},
            Path("suite.yaml"),
        )


class TestAgentMatrixEnvFilter(unittest.TestCase):
    def test_env_matrix_skips_disabled_before_env_lookup(self) -> None:
        suite = {
            "agentMatrix": {
                "gpt_5_4": {"thingName": "YamlGpt", "defaultEnabled": True},
                "sonnet_4_6": {"thingName": "YamlSonnet", "defaultEnabled": False},
            }
        }
        os.environ["AGENT_EVAL_AGENT_GPT_5_4"] = "EnvGpt"
        os.environ.pop("AGENT_EVAL_AGENT_SONNET_4_6", None)
        try:
            matrix, skipped = resolve_agent_matrix(
                suite,
                "env",
                agent_filter=["gpt_5_4"],
                include_sonnet_env=False,
            )
        finally:
            os.environ.pop("AGENT_EVAL_AGENT_GPT_5_4", None)
        self.assertEqual(matrix, {"gpt_5_4": "EnvGpt"})
        self.assertTrue(any(lbl == "sonnet_4_6" for lbl, _ in skipped))


def _minimal_suite_yaml() -> str:
    return """
version: 1
suite: main-integration-test
agentMatrix:
  mock:
    thingName: MockAgent
cases:
  - id: happy_path
    turns:
      - user: "ping"
        acceptableOutcomes:
          - name: ok
            score: 1.0
            assertions:
              - finalContains: "OK"
"""


class TestMainIntegrated(unittest.TestCase):
    def setUp(self) -> None:
        self._env_patch = mock.patch.dict(
            os.environ,
            {"DEV_SERVER": "http://tw.example/", "DEV_KEY": "app-key"},
            clear=False,
        )
        self._env_patch.start()

    def tearDown(self) -> None:
        self._env_patch.stop()

    def test_main_success_path_mocked_rest(self) -> None:
        final_text = "OK"
        post_rows = [{"role": "assistant", "content": final_text, "toolCalls": ""}]
        stream_reads = [[], post_rows]

        def fake_query_stream(*_a: object, **_k: object) -> list[dict[str, object]]:
            if stream_reads:
                return stream_reads.pop(0)  # type: ignore[return-value]
            return post_rows

        with tempfile.TemporaryDirectory() as td:
            suite_path = Path(td) / "suite.yaml"
            suite_path.write_text(_minimal_suite_yaml(), encoding="utf-8")
            out_base = Path(td) / "out"
            with (
                mock.patch(
                    "test_scripts.agent_eval.get_or_create_conversation_id",
                    return_value="conv-1",
                ),
                mock.patch("test_scripts.agent_eval.query_stream_data", side_effect=fake_query_stream),
                mock.patch("test_scripts.agent_eval.run_turn_chat", return_value=final_text),
                mock.patch("test_scripts.agent_eval.time.sleep", return_value=None),
            ):
                with self.assertRaises(SystemExit) as ctx:
                    main(
                        [
                            "--suite",
                            str(suite_path),
                            "--agent-matrix",
                            "yaml",
                            "--out-dir",
                            str(out_base),
                            "--stream-buffer-wait-s",
                            "0",
                        ]
                    )
                self.assertEqual(ctx.exception.code, EXIT_OK)
            out_dirs = list(out_base.iterdir())
            self.assertEqual(len(out_dirs), 1)
            report = json.loads((out_dirs[0] / "report.json").read_text(encoding="utf-8"))
            case_rows = [r for r in report["results"] if r.get("kind") == "case"]
            self.assertEqual(len(case_rows), 1)
            self.assertEqual(case_rows[0]["status"], "ok")
            self.assertEqual(case_rows[0]["caseId"], "happy_path")
            turns = case_rows[0].get("turns") or []
            self.assertEqual(turns[0].get("selectedOutcome"), "ok")

    def test_setup_clear_failure_flushes_partial(self) -> None:
        suite_yaml = _minimal_suite_yaml().replace(
            "  - id: happy_path",
            "  - id: clear_fails\n    resetMode: stable_clear",
            1,
        )
        partial_reports: list[dict[str, object]] = []

        def track_partial(out_dir: Path, report: dict[str, object]) -> None:
            partial_reports.append(json.loads(json.dumps(report)))
            flush_partial_report(out_dir, report)

        clear_err = InfraHttpError(500, "clear failed", "http://x/ClearConversation", "body")

        with tempfile.TemporaryDirectory() as td:
            suite_path = Path(td) / "suite.yaml"
            suite_path.write_text(suite_yaml, encoding="utf-8")
            out_base = Path(td) / "out"
            with (
                mock.patch(
                    "test_scripts.agent_eval.get_or_create_conversation_id",
                    return_value="conv-1",
                ),
                mock.patch("test_scripts.agent_eval.clear_conversation", side_effect=clear_err),
                mock.patch("test_scripts.agent_eval.flush_partial_report", side_effect=track_partial),
            ):
                with self.assertRaises(SystemExit) as ctx:
                    main(
                        [
                            "--suite",
                            str(suite_path),
                            "--agent-matrix",
                            "yaml",
                            "--out-dir",
                            str(out_base),
                        ]
                    )
                self.assertEqual(ctx.exception.code, EXIT_INFRA_FAIL)
            self.assertTrue(partial_reports)
            last = partial_reports[-1]
            rows = [r for r in last.get("results", []) if isinstance(r, dict)]
            clear_rows = [r for r in rows if r.get("phase") == "clear_conversation"]
            self.assertEqual(len(clear_rows), 1)
            self.assertEqual(clear_rows[0].get("status"), "infra_error")


class TestAgentMatrixParsing(unittest.TestCase):
    def test_string_entry_default_enabled(self) -> None:
        thing, de = parse_agent_matrix_entry("AgentThing")
        self.assertEqual(thing, "AgentThing")
        self.assertTrue(de)

    def test_object_sonnet_disabled_by_default(self) -> None:
        suite = {
            "agentMatrix": {
                "gpt_5_4": {"thingName": "Gpt", "defaultEnabled": True},
                "sonnet_4_6": {"thingName": "Sonnet", "defaultEnabled": False},
            }
        }
        matrix, skipped = resolve_agent_matrix(suite, "yaml", agent_filter=None, include_sonnet_env=False)
        self.assertIn("gpt_5_4", matrix)
        self.assertNotIn("sonnet_4_6", matrix)
        self.assertTrue(any(lbl == "sonnet_4_6" for lbl, _ in skipped))


if __name__ == "__main__":
    unittest.main()
