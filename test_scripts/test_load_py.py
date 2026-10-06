"""Offline tests for the root load.py import tool (no ThingWorx contact)."""

from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("parler_load", REPO_ROOT / "load.py")
load = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(load)


class ServerRootTest(unittest.TestCase):
    def test_strips_one_context_path_and_keeps_the_host(self):
        cases = {
            "https://demo.org/Thingworx": "https://demo.org",
            "https://twx/Thingworx": "https://twx",
            "https://host:8443/thingworx": "https://host:8443",
            "https://example.com/Thingworx/": "https://example.com",
            "https://example.com": "https://example.com",
        }
        for base, root in cases.items():
            with self.subTest(base=base):
                self.assertEqual(root, load.server_root(base))

    def test_rest_call_targets_the_configured_host(self):
        for base, expected in [
            ("https://demo.org/Thingworx", "https://demo.org/Thingworx/Things/T/Services/S"),
            ("https://twx/thingworx", "https://twx/Thingworx/Things/T/Services/S"),
        ]:
            with self.subTest(base=base), mock.patch.object(load, "execute_rest_call") as call:
                load.action_rest_call({"service": "/Thingworx/Things/T/Services/S"}, base, "key", REPO_ROOT, {})
                self.assertEqual(expected, call.call_args.args[0])


def _report(status: int, message: str) -> str:
    return json.dumps({"rows": [{"install": {"rows": [{"extensionReportStatus": status, "reportMessage": message}]}}]})


def _log_response(rows: list[dict]) -> mock.MagicMock:
    resp = mock.MagicMock()
    resp.__enter__.return_value.read.return_value = json.dumps({"rows": rows}).encode()
    return resp


class ExtensionImportTest(unittest.TestCase):
    def _import(self, code: int, body: str, log=None) -> str:
        error = urllib.error.HTTPError("https://twx/Thingworx/ExtensionPackageUploader", code, "x", {},
                                       io.BytesIO(body.encode()))
        log_reply = log if log is not None else urllib.error.URLError("log not reachable")
        with tempfile.TemporaryDirectory() as tmp:
            zip_path = Path(tmp) / "ext.zip"
            zip_path.write_bytes(b"PK\x05\x06" + b"\0" * 18)
            stderr = io.StringIO()
            with mock.patch.object(load.urllib.request, "urlopen", side_effect=[error, log_reply]) as urlopen, \
                    redirect_stderr(stderr):
                load.action_import_extension({"files": [str(zip_path)]}, "https://twx/Thingworx", "key", Path(tmp))
            self.urlopen = urlopen
            return stderr.getvalue()

    def test_http_406_warns_and_prints_the_application_log(self):
        rows = [
            {"level": "ERROR", "origin": "c.t.webservices.ExtensionPackageUploader",
             "content": "Extension Exception: Extension : A newer version of parler-agent is already installed"},
            {"level": "WARN", "origin": "c.t.things.SomeThing", "content": "unrelated warning"},
        ]
        out = self._import(406, "Not Acceptable", _log_response(rows))
        self.assertIn("HTTP 406: WARNING, continuing", out)
        self.assertIn("Not Acceptable", out)
        self.assertIn("A newer version of parler-agent is already installed", out)
        self.assertNotIn("unrelated warning", out)
        query = self.urlopen.call_args_list[1].args[0]
        self.assertEqual("https://twx/Thingworx/Logs/ApplicationLog/Services/QueryLogEntries", query.full_url)
        self.assertEqual("WARN", json.loads(query.data)["fromLogLevel"])

    def test_http_406_continues_when_the_log_cannot_be_read(self):
        out = self._import(406, "Not Acceptable")
        self.assertIn("could not read the ApplicationLog", out)

    def test_http_406_prints_the_report_problems(self):
        out = self._import(406, _report(1, "Extension X failed to install."), _log_response([]))
        self.assertIn("install FAILURE: Extension X failed to install.", out)
        self.assertIn("no matching ApplicationLog entries", out)

    def test_other_errors_still_stop_the_plan(self):
        with self.assertRaises(SystemExit):
            self._import(500, "Internal Server Error")

    def test_report_problems_ignore_warnings(self):
        self.assertEqual([], load.extension_report_problems(_report(2, "skipped because it is already installed.")))
        self.assertEqual(["install ALERT: check"], load.extension_report_problems(_report(3, "check")))
        self.assertEqual([], load.extension_report_problems("Not Acceptable"))


if __name__ == "__main__":
    unittest.main()
