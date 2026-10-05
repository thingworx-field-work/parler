"""Unit tests for utilization stage smoke path resolution."""

from __future__ import annotations

import os
import unittest
from unittest import mock

from test_scripts.run_utilization_stage_smoke import REPO_ROOT, resolve_suite_path


class ResolveSuitePathTest(unittest.TestCase):
    def test_default_suite_is_in_this_repository(self) -> None:
        suite = resolve_suite_path()
        self.assertEqual(suite, REPO_ROOT / "docs/agent/evals/utilization_stage_contracts_v1.yaml")
        self.assertTrue(suite.is_file())

    def test_has_dev_credentials(self) -> None:
        from test_scripts.run_utilization_stage_smoke import has_dev_credentials

        with mock.patch.dict(os.environ, {"DEV_SERVER": "http://x", "DEV_KEY": "k"}, clear=False):
            self.assertTrue(has_dev_credentials())
        with mock.patch.dict(os.environ, {"DEV_SERVER": "", "DEV_KEY": "k"}, clear=False):
            self.assertFalse(has_dev_credentials())


if __name__ == "__main__":
    unittest.main()
