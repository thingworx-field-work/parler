"""Unit tests for ThingWorx REST result normalization used by ``agent_eval``."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from test_scripts.agent_eval import (
    TRUNCATION_MARKER,
    InfraHttpError,
    aggregate_llm_usage_from_delta,
    build_query_stream_data_payload,
    build_turn_context,
    eval_assertion_list,
    extract_rows_from_service_result,
    extract_scalar_result,
    format_reject_hit,
    parse_llm_usage_json_cell,
    render_model_comparison_markdown,
    render_markdown,
    select_outcome,
    stable_hard_reset_stream,
    trace_complete_strict,
    trace_matches_chat_return,
)


class TestExtractScalarResult(unittest.TestCase):
    def test_raw_string(self) -> None:
        self.assertEqual(extract_scalar_result("OK"), "OK")

    def test_top_level_result_string(self) -> None:
        self.assertEqual(extract_scalar_result({"result": "OK"}), "OK")

    def test_top_level_result_empty_string(self) -> None:
        self.assertEqual(extract_scalar_result({"result": ""}), "")

    def test_infotable_rows_single_row(self) -> None:
        payload = {"dataShape": {"name": "x"}, "rows": [{"result": "assistant text"}]}
        self.assertEqual(extract_scalar_result(payload), "assistant text")

    def test_infotable_rows_empty_result_cell(self) -> None:
        payload = {"rows": [{"result": ""}]}
        self.assertEqual(extract_scalar_result(payload), "")

    def test_result_wraps_infotable(self) -> None:
        payload = {"result": {"rows": [{"result": "nested"}]}}
        self.assertEqual(extract_scalar_result(payload), "nested")

    def test_zero_rows_returns_none(self) -> None:
        self.assertIsNone(extract_scalar_result({"rows": []}))

    def test_numeric_scalar_via_rows(self) -> None:
        self.assertEqual(extract_scalar_result({"rows": [{"result": 42}]}), "42")


class TestTraceMatch(unittest.TestCase):
    def test_exact_match(self) -> None:
        delta = [{"role": "assistant", "content": "ok"}]
        self.assertTrue(trace_matches_chat_return(delta, "ok"))

    def test_truncated_stream_prefix(self) -> None:
        ft = "hello world extended"
        delta = [{"role": "assistant", "content": "hello world" + TRUNCATION_MARKER}]
        self.assertTrue(trace_matches_chat_return(delta, ft))


class TestTraceCompleteStrict(unittest.TestCase):
    def test_complete_trace_with_final_assistant_passes(self) -> None:
        delta = [
            {"role": "user", "content": "go"},
            {"role": "assistant", "content": "", "toolCalls": '[{"name":"x","arguments":{}}]'},
            {"role": "tool", "content": "ok"},
            {"role": "assistant", "content": "X", "toolCalls": ""},
        ]
        self.assertTrue(trace_complete_strict(delta, "X"))

    def test_intermediate_assistant_toolcall_row_rejected(self) -> None:
        delta = [{"role": "assistant", "content": "", "toolCalls": '[{"name":"x","arguments":{}}]'}]
        self.assertFalse(trace_complete_strict(delta, "should not accept tool-call tail"))

    def test_truncated_stream_content_handled(self) -> None:
        ft = "hello world extended"
        delta = [{"role": "assistant", "content": "hello world" + TRUNCATION_MARKER, "toolCalls": None}]
        self.assertTrue(trace_complete_strict(delta, ft))

    def test_last_row_not_assistant(self) -> None:
        delta = [{"role": "assistant", "content": "x", "toolCalls": ""}, {"role": "tool", "content": "y"}]
        self.assertFalse(trace_complete_strict(delta, "x"))

    def test_empty_delta(self) -> None:
        self.assertFalse(trace_complete_strict([], "x"))


class TestFormatRejectHit(unittest.TestCase):
    def test_final_contains(self) -> None:
        s = format_reject_hit("finalContains", "HIERARCHY_RESOLVE_NOT_FOUND")
        self.assertIn("forbidden", s)
        self.assertIn("HIERARCHY_RESOLVE_NOT_FOUND", s)


class TestQueryStreamPayload(unittest.TestCase):
    def test_minimal_keys_no_nulls(self) -> None:
        p = build_query_stream_data_payload(source="conv-uuid", max_items=500)
        self.assertEqual(set(p), {"maxItems", "source", "oldestFirst"})
        self.assertEqual(p["source"], "conv-uuid")
        self.assertEqual(p["oldestFirst"], False)
        self.assertEqual(p["maxItems"], 500)
        self.assertNotIn(None, p.values())


class TestExtractRowsFromServiceResult(unittest.TestCase):
    def test_top_level_rows(self) -> None:
        rows = extract_rows_from_service_result({"rows": [{"a": 1}]})
        self.assertEqual(rows, [{"a": 1}])

    def test_nested_result_rows(self) -> None:
        rows = extract_rows_from_service_result({"result": {"rows": [{"b": 2}]}})
        self.assertEqual(rows, [{"b": 2}])


class TestParseLlmUsageJsonCell(unittest.TestCase):
    def test_empty_none(self) -> None:
        self.assertEqual(parse_llm_usage_json_cell(None), (None, None))
        self.assertEqual(parse_llm_usage_json_cell("  "), (None, None))

    def test_truncated(self) -> None:
        v, err = parse_llm_usage_json_cell('{"a":1' + TRUNCATION_MARKER)
        self.assertIsNone(v)
        self.assertEqual(err, "llm_usage_json_truncated_in_stream")

    def test_invalid_json(self) -> None:
        v, err = parse_llm_usage_json_cell("{not json")
        self.assertIsNone(v)
        self.assertEqual(err, "llm_usage_json_parse_error")


class TestAggregateLlmUsage(unittest.TestCase):
    def test_legacy_columns_when_missing_json(self) -> None:
        rows = [
            {"role": "user", "promptTokens": 9},
            {"role": "assistant", "promptTokens": 10, "completionTokens": 2, "llmUsageJson": ""},
        ]
        pt, ct, it, ot, cr, cc, cp, rt, rids, byp, errs = aggregate_llm_usage_from_delta(rows)
        self.assertEqual(pt, 10)
        self.assertEqual(ct, 2)
        self.assertEqual(it, 10)
        self.assertEqual(ot, 2)
        self.assertEqual(errs, [])
        self.assertIn("column_only", byp)

    def test_valid_json_sums(self) -> None:
        j = '{"provider":"AZURE_OPEN_AI","requestId":"r1","inputTokens":5,"outputTokens":2,'
        j += '"promptTokens":5,"completionTokens":2,"cacheReadInputTokens":1,'
        j += '"cacheCreationInputTokens":0,"cachedPromptTokens":3,"reasoningTokens":0}'
        rows = [{"role": "assistant", "promptTokens": 5, "completionTokens": 2, "llmUsageJson": j}]
        pt, ct, it, ot, cr, cc, cp, rt, rids, byp, errs = aggregate_llm_usage_from_delta(rows)
        self.assertEqual(errs, [])
        self.assertEqual(rids, ["r1"])
        self.assertEqual(cr, 1)
        self.assertEqual(cp, 3)
        self.assertIn("AZURE_OPEN_AI", byp)

    def test_malformed_falls_back_to_columns(self) -> None:
        rows = [{"role": "assistant", "promptTokens": 7, "completionTokens": 1, "llmUsageJson": "{"}]
        pt, ct, it, ot, cr, cc, cp, rt, rids, byp, errs = aggregate_llm_usage_from_delta(rows)
        self.assertEqual(errs, ["llm_usage_json_parse_error"])
        self.assertEqual(pt, 7)
        self.assertEqual(it, 7)


class TestStableHardResetStream(unittest.TestCase):
    def test_drains_multiple_query_pages(self) -> None:
        state = {"q": 0}

        def fake_post(url: str, app_key: str, payload: dict, *, timeout_s: float):
            if "QueryStreamEntries" in url:
                state["q"] += 1
                if state["q"] == 1:
                    return {"rows": [{"id": "a"}, {"id": "b"}]}
                if state["q"] == 2:
                    return {"rows": [{"id": "c"}]}
                return {"rows": []}
            if "DeleteStreamEntry" in url:
                return {}
            raise AssertionError(url)

        with patch("test_scripts.agent_eval.post_json", side_effect=fake_post):
            deleted, rem, err = stable_hard_reset_stream(
                "http://localhost/Thingworx",
                "appkey",
                conversation_id="conv-1",
                page_size=20000,
                max_query_rounds=20,
                timeout_s=1.0,
            )
        self.assertIsNone(err)
        self.assertEqual(deleted, 3)
        self.assertEqual(rem, 0)

    def test_safety_limit(self) -> None:
        def fake_post(url: str, app_key: str, payload: dict, *, timeout_s: float):
            if "QueryStreamEntries" in url:
                return {"rows": [{"id": "x"}]}
            if "DeleteStreamEntry" in url:
                return {}
            raise AssertionError(url)

        with patch("test_scripts.agent_eval.post_json", side_effect=fake_post):
            deleted, rem, err = stable_hard_reset_stream(
                "http://localhost/Thingworx",
                "appkey",
                conversation_id="conv-1",
                page_size=20000,
                max_query_rounds=2,
                timeout_s=1.0,
            )
        self.assertEqual(err, "stable_hard_reset_safety_limit_exceeded")
        self.assertGreaterEqual(deleted, 2)
        self.assertGreater(rem, 0)

    def test_query_error_returns_structured_tuple(self) -> None:
        calls = {"q": 0}

        def fake_post(url: str, app_key: str, payload: dict, *, timeout_s: float):
            if "QueryStreamEntries" in url:
                calls["q"] += 1
                if calls["q"] == 1:
                    raise InfraHttpError(500, "Internal", url, "body")
                return {"rows": []}
            raise AssertionError(url)

        with patch("test_scripts.agent_eval.post_json", side_effect=fake_post):
            deleted, rem, err = stable_hard_reset_stream(
                "http://localhost/Thingworx",
                "appkey",
                conversation_id="conv-1",
                page_size=20000,
                max_query_rounds=5,
                timeout_s=1.0,
            )
        self.assertEqual(deleted, 0)
        self.assertIn("stream_hard_reset_query_error", err or "")

    def test_delete_error_returns_structured_tuple(self) -> None:
        calls = {"q": 0}

        def fake_post(url: str, app_key: str, payload: dict, *, timeout_s: float):
            if "QueryStreamEntries" in url:
                calls["q"] += 1
                if calls["q"] == 1:
                    return {"rows": [{"id": "a"}]}
                return {"rows": []}
            if "DeleteStreamEntry" in url:
                raise InfraHttpError(404, "Not Found", url, "{}")
            raise AssertionError(url)

        with patch("test_scripts.agent_eval.post_json", side_effect=fake_post):
            deleted, rem, err = stable_hard_reset_stream(
                "http://localhost/Thingworx",
                "appkey",
                conversation_id="conv-1",
                page_size=20000,
                max_query_rounds=5,
                timeout_s=1.0,
            )
        self.assertEqual(deleted, 0)
        self.assertIn("stream_hard_reset_delete_error", err or "")


class TestRenderMarkdownHardReset(unittest.TestCase):
    def test_passed_case_shows_zero_deleted_rows(self) -> None:
        report = {
            "meta": {},
            "results": [
                {
                    "status": "ok",
                    "caseId": "c1",
                    "agentLabel": "L",
                    "conversationId": "cid",
                    "caseScore": 1.0,
                    "resetMode": "stable_hard_reset",
                    "conversationTitle": "eval:t:c:L",
                    "streamRowsDeleted": 0,
                    "streamRowsRemainingAfterReset": 0,
                    "turns": [],
                }
            ],
        }
        md = render_markdown(report)
        self.assertIn("deleted `0`", md)
        self.assertIn("remaining `0`", md)

    def test_fresh_infra_failure_does_not_show_stream_reset_counters(self) -> None:
        report = {
            "meta": {},
            "results": [
                {
                    "status": "infra_error",
                    "caseId": "c1",
                    "agentLabel": "L",
                    "agentThing": "AgentThing",
                    "resetMode": "fresh",
                    "conversationTitle": "eval:s:c:L:uuid",
                    "streamRowsDeleted": 0,
                    "streamRowsRemainingAfterReset": 0,
                    "error": "HTTP 500",
                    "turns": [],
                }
            ],
        }
        md = render_markdown(report)
        self.assertNotIn("streamRowsDeleted", md)
        self.assertNotIn("streamRowsRemainingAfterReset", md)


class TestRenderModelComparisonMarkdown(unittest.TestCase):
    def test_includes_full_final_text(self) -> None:
        report = {
            "meta": {"suite": "s", "agentMatrixMode": "yaml"},
            "results": [
                {
                    "status": "ok",
                    "casePass": True,
                    "caseId": "c1",
                    "agentLabel": "gpt",
                    "agentThing": "AgentThing",
                    "caseScore": 1.0,
                    "conversationId": "cid",
                    "turns": [
                        {
                            "turnIndex": 0,
                            "skipped": False,
                            "selectedOutcome": "ok",
                            "score": 1.0,
                            "pass": True,
                            "finalExcerpt": "short",
                            "finalText": "full final answer body",
                            "orderedToolCalls": ["tool_a"],
                            "usageByProvider": {
                                "AZURE_OPEN_AI": {
                                    "inputTokens": 10,
                                    "outputTokens": 2,
                                    "cachedPromptTokens": 8,
                                }
                            },
                        }
                    ],
                }
            ],
        }
        md = render_model_comparison_markdown(report)
        self.assertIn("full final answer body", md)
        self.assertIn("tool_a", md)
        self.assertIn("| c1 | gpt | `AgentThing` | ok | 1.0 | 10 | 2 | 8 |", md)


class TestCompositeAssertions(unittest.TestCase):
    def test_all_of_reject_allows_recovered_hierarchy_failure(self) -> None:
        final_text = (
            "Recovered after HIERARCHY_RESOLVE_NOT_FOUND. "
            "Resolved SE.CellFab.Model.Workunit.ORD-JetDryer-02 and "
            "SE.CellFab.Model.Workunit.AC-JetDryer-01."
        )
        ctx = build_turn_context(final_text, [])
        oname, score, fails, rejects = select_outcome(
            [
                {
                    "name": "resolved",
                    "score": 1.0,
                    "assertions": [
                        {"finalContains": "SE.CellFab.Model.Workunit.ORD-JetDryer-02"},
                        {"finalContains": "SE.CellFab.Model.Workunit.AC-JetDryer-01"},
                    ],
                }
            ],
            [
                {
                    "allOf": [
                        {"finalContains": "HIERARCHY_RESOLVE_NOT_FOUND"},
                        {"finalNotContains": "SE.CellFab.Model.Workunit.ORD-JetDryer-02"},
                        {"finalNotContains": "SE.CellFab.Model.Workunit.AC-JetDryer-01"},
                    ]
                }
            ],
            ctx,
            ordered_tool_names=[],
            tool_calls_flat=[],
            tool_result_texts=[],
            assistant_row_count=1,
        )
        self.assertEqual(oname, "resolved")
        self.assertEqual(score, 1.0)
        self.assertEqual(fails, [])
        self.assertEqual(rejects, [])

    def test_all_of_reject_fires_for_unrecovered_hierarchy_failure(self) -> None:
        final_text = "I could not resolve the assets: HIERARCHY_RESOLVE_NOT_FOUND."
        ctx = build_turn_context(final_text, [])
        oname, score, fails, rejects = select_outcome(
            [
                {
                    "name": "resolved",
                    "score": 1.0,
                    "assertions": [{"finalContains": "SE.CellFab.Model.Workunit.ORD-JetDryer-02"}],
                }
            ],
            [
                {
                    "allOf": [
                        {"finalContains": "HIERARCHY_RESOLVE_NOT_FOUND"},
                        {"finalNotContains": "SE.CellFab.Model.Workunit.ORD-JetDryer-02"},
                        {"finalNotContains": "SE.CellFab.Model.Workunit.AC-JetDryer-01"},
                    ]
                }
            ],
            ctx,
            ordered_tool_names=[],
            tool_calls_flat=[],
            tool_result_texts=[],
            assistant_row_count=1,
        )
        self.assertIsNone(oname)
        self.assertEqual(score, 0.0)
        self.assertEqual(fails, [])
        self.assertTrue(rejects)
        self.assertIn("rejectIf:allOf", rejects[0])

    def test_any_of_and_not_can_be_used_in_assertions(self) -> None:
        ctx = build_turn_context("The final answer is healthy.", [])
        ok, failures = eval_assertion_list(
            [
                {"anyOf": [{"finalContains": "broken"}, {"finalContains": "healthy"}]},
                {"not": {"finalContains": "HIERARCHY_RESOLVE_NOT_FOUND"}},
            ],
            ctx,
            ordered_tool_names=[],
            tool_calls_flat=[],
            tool_result_texts=[],
            assistant_row_count=1,
        )
        self.assertTrue(ok)
        self.assertEqual(failures, [])


if __name__ == "__main__":
    unittest.main()
