"""Unit tests for ``collect`` helpers (stdlib only)."""

from __future__ import annotations

import json
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

try:
    import collection_tool.collect as collect_module
    from collection_tool.collect import (
        build_application_log_url,
        build_file_repository_download_url,
        build_log_search_regex,
        candidate_paths_from_listing,
        drift_checks_from_parsed_snapshot,
        get_binary,
        local_path_for_repository_file,
        normalize_application_log_row,
        normalize_log_timestamp,
        normalize_repository_path,
        normalize_stream_row,
        parse_window,
        redact_value,
        safe_fs_component,
        scalar_to_json_for_agent_status,
        snapshot_repository_paths,
    )
except ModuleNotFoundError:
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import collect as collect_module
    from collect import (
        build_application_log_url,
        build_file_repository_download_url,
        build_log_search_regex,
        candidate_paths_from_listing,
        drift_checks_from_parsed_snapshot,
        get_binary,
        local_path_for_repository_file,
        normalize_application_log_row,
        normalize_log_timestamp,
        normalize_repository_path,
        normalize_stream_row,
        parse_window,
        redact_value,
        safe_fs_component,
        scalar_to_json_for_agent_status,
        snapshot_repository_paths,
    )


class CollectUnitTest(unittest.TestCase):
    def test_build_application_log_url_no_duplicate_thingworx(self) -> None:
        u1 = build_application_log_url("https://host.example/Thingworx", "ApplicationLog")
        self.assertTrue(u1.endswith("/Logs/ApplicationLog/Services/QueryLogEntries"))
        self.assertEqual(u1.count("Thingworx"), 1)
        u2 = build_application_log_url("https://host.example", "ApplicationLog")
        self.assertIn("/Thingworx/Logs/ApplicationLog/Services/QueryLogEntries", u2)
        self.assertEqual(u2.count("Thingworx"), 1)

    def test_build_file_repository_download_url_encodes_segments(self) -> None:
        u = build_file_repository_download_url(
            "https://host.example/Thingworx",
            "Config Repo",
            "/skills/Asset Pair/SKILL.md",
        )
        self.assertEqual(
            u,
            "https://host.example/Thingworx/FileRepositories/Config%20Repo/skills/Asset%20Pair/SKILL.md",
        )
        u2 = build_file_repository_download_url("https://host.example", "R", "taxonomies/identity-types.json")
        self.assertEqual(u2.count("Thingworx"), 1)
        self.assertIn("/FileRepositories/R/taxonomies/identity-types.json", u2)

    def test_get_binary_rejects_declared_oversized_file(self) -> None:
        class FakeResponse:
            headers = {"Content-Length": "5"}

            def __enter__(self) -> "FakeResponse":
                return self

            def __exit__(self, *args: object) -> None:
                return None

            def read(self, n: int) -> bytes:
                return b"abcde"

        with patch(f"{collect_module.__name__}.urllib.request.urlopen", return_value=FakeResponse()):
            with self.assertRaisesRegex(RuntimeError, "before download"):
                get_binary("https://host/file", "k", timeout_s=1, max_bytes=4)

    def test_get_binary_rejects_streamed_oversized_file(self) -> None:
        class FakeResponse:
            headers: dict[str, str] = {}

            def __enter__(self) -> "FakeResponse":
                return self

            def __exit__(self, *args: object) -> None:
                return None

            def read(self, n: int) -> bytes:
                return b"abcde"

        with patch(f"{collect_module.__name__}.urllib.request.urlopen", return_value=FakeResponse()):
            with self.assertRaisesRegex(RuntimeError, "after download"):
                get_binary("https://host/file", "k", timeout_s=1, max_bytes=4)

    def test_parse_window(self) -> None:
        self.assertEqual(parse_window("1h"), timedelta(hours=1))
        self.assertEqual(parse_window("15m"), timedelta(minutes=15))
        self.assertEqual(parse_window("2h"), timedelta(hours=2))
        self.assertEqual(parse_window("30"), timedelta(minutes=30))

    def test_build_log_search_regex(self) -> None:
        self.assertEqual(build_log_search_regex(None, []), ".*.*")
        r = build_log_search_regex("ab.c", ["r1"])
        self.assertIn("ab\\.c", r)
        self.assertIn("r1", r)

    def test_drift_from_snapshot_files(self) -> None:
        parsed = {
            "configurationRepository": {
                "files": [
                    {
                        "path": "/x.md",
                        "loadedSha256": "aa",
                        "sha256": "bb",
                    }
                ]
            }
        }
        d = drift_checks_from_parsed_snapshot(parsed)
        self.assertEqual(len(d), 1)
        self.assertEqual(d[0]["confidence"], "hash")

    def test_drift_equal_hashes_produces_no_check(self) -> None:
        parsed = {
            "configurationRepository": {
                "files": [
                    {
                        "path": "/skills/A/SKILL.md",
                        "loadedSha256": "aa",
                        "sha256": "aa",
                    }
                ]
            }
        }
        self.assertEqual(drift_checks_from_parsed_snapshot(parsed), [])

    def test_snapshot_repository_paths_includes_standard_and_snapshot_files(self) -> None:
        paths = snapshot_repository_paths(
            {
                "configurationRepository": {
                    "files": [
                        {"path": "/skills/A/SKILL.md"},
                        {"path": "playbooks/P/playbook.json"},
                        {"path": "../bad"},
                    ]
                }
            }
        )
        self.assertIn("/taxonomies/identity-types.json", paths)
        self.assertIn("/tools/extended_tools.json", paths)
        self.assertIn("/skills/A/SKILL.md", paths)
        self.assertIn("/playbooks/P/playbook.json", paths)
        self.assertNotIn("/bad", paths)

    def test_candidate_paths_from_listing(self) -> None:
        skills = candidate_paths_from_listing(
            "skills",
            [
                {"name": "asset_pair_health", "isDirectory": True},
                {"name": "README.md", "isDirectory": False},
            ],
        )
        self.assertEqual(skills, {"/skills/asset_pair_health/SKILL.md"})
        playbooks = candidate_paths_from_listing("playbooks", [{"name": "cross_asset_pair_health", "type": "folder"}])
        self.assertEqual(playbooks, {"/playbooks/cross_asset_pair_health/playbook.json"})
        policies = candidate_paths_from_listing("policies", [{"name": "invoke_service.json", "isDirectory": False}])
        self.assertEqual(policies, {"/policies/invoke_service.json"})

    def test_local_repository_path_safety(self) -> None:
        self.assertEqual(normalize_repository_path("skills/A/SKILL.md"), "/skills/A/SKILL.md")
        self.assertIsNone(normalize_repository_path("/skills/../secret.txt"))
        self.assertEqual(safe_fs_component("Config Repo: A"), "Config_Repo_A")
        target = local_path_for_repository_file(Path("/tmp/root"), "/skills/A/SKILL.md")
        self.assertEqual(str(target), "/tmp/root/skills/A/SKILL.md")

    def test_runtime_snapshot_json_is_object_not_string(self) -> None:
        raw = '{"configurationRepository": {"thingName": "R", "status": "ok", "files": []}}'
        warns: list[str] = []
        parsed = scalar_to_json_for_agent_status(raw, warns, "test")
        self.assertIsInstance(parsed, dict)
        self.assertIsInstance(parsed.get("configurationRepository"), dict)

    def test_redact_nested_secret(self) -> None:
        obj = {"ok": 1, "nested": {"apiKey": "secret", "x": 2}}
        out = redact_value(obj)
        self.assertEqual(out["ok"], 1)
        self.assertEqual(out["nested"]["apiKey"], "[REDACTED]")
        self.assertEqual(out["nested"]["x"], 2)

    def test_redact_parsed_runtime_nested_secret(self) -> None:
        doc = redact_value(
            {
                "agents": [
                    {
                        "runtimeSnapshot": {
                            "json": {"policies": {"invoke_service": {"apiKey": "leak"}}},
                        }
                    }
                ]
            }
        )
        nested = doc["agents"][0]["runtimeSnapshot"]["json"]["policies"]["invoke_service"]["apiKey"]
        self.assertEqual(nested, "[REDACTED]")

    def test_redact_preserves_llm_usage_token_counters(self) -> None:
        u = {"promptTokens": 10, "completionTokens": 5, "inputTokens": 3, "apiKey": "x"}
        out = redact_value(u)
        self.assertEqual(out["promptTokens"], 10)
        self.assertEqual(out["completionTokens"], 5)
        self.assertEqual(out["inputTokens"], 3)
        self.assertEqual(out["apiKey"], "[REDACTED]")

    def test_normalize_log_timestamp_epoch_ms(self) -> None:
        # 2020-01-01 00:00:00 UTC in ms
        t = normalize_log_timestamp(1577836800000)
        self.assertTrue(t.endswith("Z"), t)
        self.assertIn("T", t)

    def test_normalize_log_timestamp_numeric_string_ms(self) -> None:
        t = normalize_log_timestamp("1577836800000")
        self.assertTrue(t.endswith("Z"), t)

    def test_normalize_application_log_row_timestamp(self) -> None:
        row = normalize_application_log_row({"timestamp": 1577836800000, "content": "x"})
        self.assertTrue(str(row["timestamp"]).endswith("Z"))

    def test_normalize_stream_row_parses_tool_calls_companion_redacts_credential_keys(self) -> None:
        payload = [{"name": "x", "args": {"apiKey": "secret", "ok": 1}}]
        raw = json.dumps(payload)
        row = normalize_stream_row(
            {
                "timestamp": 1577836800000,
                "toolCalls": raw,
                "values": {},
            }
        )
        self.assertEqual(row["toolCalls"], raw, "verbatim platform string preserved for internal training")
        self.assertIsInstance(row["toolCallsJson"], list)
        self.assertEqual(row["toolCallsJson"][0]["args"]["apiKey"], "[REDACTED]")
        self.assertEqual(row["toolCallsJson"][0]["args"]["ok"], 1)
        reparsed = json.loads(row["toolCalls"])
        self.assertEqual(reparsed[0]["args"]["apiKey"], "secret")

    def test_normalize_stream_row_nested_function_arguments_verbatim_and_companion(self) -> None:
        secret = "NESTED_SECRET_ARG"
        inner = json.dumps({"apiKey": secret, "k": 2})
        raw = json.dumps([{"function": {"name": "fn", "arguments": inner}}])
        row = normalize_stream_row(
            {
                "timestamp": 1577836800000,
                "values": {},
                "toolCalls": raw,
            }
        )
        self.assertEqual(row["toolCalls"], raw)
        inner_args = row["toolCallsJson"][0]["function"]["arguments"]
        self.assertIsInstance(inner_args, dict)
        self.assertEqual(inner_args["apiKey"], "[REDACTED]")
        self.assertEqual(inner_args["k"], 2)

    def test_normalize_stream_row_top_level_arguments_access_token_redacted_in_companion(self) -> None:
        secret = "TOP_LEVEL_OAUTH_SECRET"
        raw = json.dumps([{"arguments": json.dumps({"access_token": secret, "scope": "a"})}])
        row = normalize_stream_row(
            {
                "timestamp": 1577836800000,
                "values": {},
                "toolCalls": raw,
            }
        )
        self.assertEqual(row["toolCalls"], raw)
        args_obj = row["toolCallsJson"][0]["arguments"]
        self.assertIsInstance(args_obj, dict)
        self.assertEqual(args_obj["access_token"], "[REDACTED]")
        self.assertEqual(args_obj["scope"], "a")

    def test_llm_usage_counters_preserved_in_stream_row(self) -> None:
        usage = {"promptTokens": 42, "completionTokens": 7, "reasoningTokens": 2, "apiKey": "hide-me"}
        raw = json.dumps(usage)
        row = normalize_stream_row(
            {
                "timestamp": 1577836800000,
                "llmUsageJson": raw,
                "values": {},
            }
        )
        self.assertEqual(row["llmUsageJson"], raw)
        self.assertEqual(row["llmUsage"]["promptTokens"], 42)
        self.assertEqual(row["llmUsage"]["completionTokens"], 7)
        self.assertEqual(row["llmUsage"]["reasoningTokens"], 2)
        self.assertEqual(row["llmUsage"]["apiKey"], "[REDACTED]")

    def test_normalize_stream_coalesces_tool_calls_from_values(self) -> None:
        tc = json.dumps([{"name": "only-in-values"}])
        row = normalize_stream_row({"timestamp": 1577836800000, "values": {"toolCalls": tc}})
        self.assertEqual(row["toolCalls"], tc)
        self.assertIsInstance(row["toolCallsJson"], list)
        self.assertEqual(row["values"]["toolCalls"], tc)
        row = normalize_stream_row({"timestamp": None, "toolCalls": "{not json", "values": {}})
        self.assertTrue(any("toolCalls:" in w for w in row["parseWarnings"]))

    def test_normalize_stream_timestamp_from_values(self) -> None:
        row = normalize_stream_row(
            {"values": {"timestamp": 1577836800000}, "toolCalls": None, "content": ""}
        )
        self.assertTrue(str(row["timestamp"]).endswith("Z"))


if __name__ == "__main__":
    unittest.main()
