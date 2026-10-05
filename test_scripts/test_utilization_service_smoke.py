"""Unit tests for utilization_service_smoke JSON unwrap helpers."""

from __future__ import annotations

import json
import unittest

from test_scripts.utilization_service_smoke import (
    assert_evidence_gap_code,
    assert_shift_summaries_equivalent,
    pick_machine_name,
    state_summary_projection,
    unwrap_json_service_result,
)


class UnwrapJsonServiceResultTest(unittest.TestCase):
    def test_direct_business_json(self) -> None:
        payload = {"status": "success", "rows": [{"utilizationState": "Running"}]}
        self.assertEqual(unwrap_json_service_result(payload), payload)

    def test_scalar_result_string(self) -> None:
        inner = {"status": "success", "stats": {"utilizationPercent": 42.0}}
        wrapped = {"result": json.dumps(inner)}
        self.assertEqual(unwrap_json_service_result(wrapped), inner)

    def test_infotable_single_row(self) -> None:
        inner = {"machineCount": 3, "rows": [{"machineName": "M1"}]}
        wrapped = {"rows": [{"result": json.dumps(inner)}]}
        self.assertEqual(unwrap_json_service_result(wrapped), inner)

    def test_overview_shape(self) -> None:
        payload = {
            "status": "success",
            "machineCoverage": {"returnedRows": 2, "rows": []},
            "stateSummary": {"rows": [{"utilizationState": "Idle"}]},
            "stats": {"utilizationPercent": 10.0},
        }
        self.assertEqual(unwrap_json_service_result(payload)["stats"]["utilizationPercent"], 10.0)

    def test_pick_machine_name_from_listing(self) -> None:
        listing = {"rows": [{"machineName": "PTC.Factory.Machine1", "displayName": "M1"}]}
        self.assertEqual(pick_machine_name(listing), "PTC.Factory.Machine1")

    def test_pick_machine_name_rejects_hint_only_listing(self) -> None:
        listing = {
            "rows": [
                {
                    "displayName": "M1",
                    "description": "Mixer one",
                    "EquipmentID": "EQ-001",
                }
            ]
        }
        with self.assertRaisesRegex(AssertionError, "canonical machineName"):
            pick_machine_name(listing)

    def test_evidence_gap_code(self) -> None:
        result = {
            "evidenceGaps": [
                {"code": "EFFECTIVE_DATES_REQUIRE_START_END", "message": "fallback"},
            ],
        }
        assert_evidence_gap_code(result, "test", "EFFECTIVE_DATES_REQUIRE_START_END")

    def test_state_summary_projection(self) -> None:
        result = {
            "rows": [
                {"utilizationState": "Running", "percentage": 12.345, "count": 3},
                {"utilizationState": "Idle", "percentage": 87.655, "count": 10},
            ],
        }
        self.assertEqual(
            state_summary_projection(result),
            (("Idle", 87.66, 10), ("Running", 12.35, 3)),
        )

    def test_shift_summaries_equivalent_pass(self) -> None:
        base = {
            "rows": [{"utilizationState": "Running", "percentage": 50.0, "count": 1}],
            "stats": {"utilizationPercent": 50.0},
        }
        assert_shift_summaries_equivalent({"All": base, "any": base, "*": base})

    def test_shift_summaries_equivalent_fails_on_projection(self) -> None:
        ref = {
            "rows": [{"utilizationState": "Running", "percentage": 50.0, "count": 1}],
            "stats": {"utilizationPercent": 50.0},
        }
        other = {
            "rows": [{"utilizationState": "Idle", "percentage": 50.0, "count": 1}],
            "stats": {"utilizationPercent": 50.0},
        }
        with self.assertRaises(AssertionError):
            assert_shift_summaries_equivalent({"All": ref, "any": other})


if __name__ == "__main__":
    unittest.main()
