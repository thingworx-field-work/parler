"""
M5 REST smokes for SCPA utilization helper services (training-stage-configuration-contracts).

Exercises the four JSON services on ``SCPA_Utilization_helper`` against a fixed SCPA
date window, including §6.3 service-variant semantics (shift normalization,
single-machine scope, effective-date fallback, overview evidence gaps).

Assertions are non-empty output plus field presence aligned with M4 playbook evidence
projection — not exact row counts (except shift-normalization row-count equality).

Reads ``DEV_SERVER`` and ``DEV_KEY`` from the repo root ``.env``. Without credentials
the script exits 0 after printing ``SKIP`` unless ``--require-live`` is set.

Run from repository root::

    uv run utilization-service-smoke
    uv run utilization-service-smoke --require-live
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Callable

from test_scripts.agent_eval import build_thing_service_url, post_json
from test_scripts.dev_import_control import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = REPO_ROOT / ".env"

THING_NAME = "SCPA_Utilization_helper"
START_DATE = "2025-09-03T00:00:00.000Z"
END_DATE = "2025-09-26T23:59:59.999Z"
HTTP_TIMEOUT_S = 120.0

SmokeFn = Callable[[str, str], None]


def has_dev_credentials() -> bool:
    return bool(os.environ.get("DEV_SERVER", "").strip() and os.environ.get("DEV_KEY", "").strip())


def unwrap_json_service_result(data: Any) -> dict[str, Any]:
    """Parse JSON-baseType helper service payloads from ThingWorx REST shapes."""
    if isinstance(data, str):
        parsed = json.loads(data)
        return parsed if isinstance(parsed, dict) else {"value": parsed}
    if not isinstance(data, dict):
        return {}
    if any(k in data for k in ("machineCoverage", "machineCount", "totalRows", "stats", "stateSummary")):
        return data
    if "status" in data and "rows" in data:
        return data
    rows = data.get("rows")
    if isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict):
        cell = rows[0].get("result")
        if isinstance(cell, str):
            parsed = json.loads(cell)
            return parsed if isinstance(parsed, dict) else {"value": parsed}
        if isinstance(cell, dict):
            return cell
    result = data.get("result")
    if isinstance(result, str):
        parsed = json.loads(result)
        return parsed if isinstance(parsed, dict) else {"value": parsed}
    if isinstance(result, dict):
        return result
    return data


def invoke_service(
    server: str,
    key: str,
    service_name: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    url = build_thing_service_url(server, THING_NAME, service_name)
    raw = post_json(url, key, payload, timeout_s=HTTP_TIMEOUT_S)
    return unwrap_json_service_result(raw)


def _positive_number(value: Any, label: str, field: str) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AssertionError(f"{label}: expected {field} > 0, got {value!r}")
    if value <= 0:
        raise AssertionError(f"{label}: expected {field} > 0, got {value!r}")


def assert_non_empty_rows(result: dict[str, Any], label: str) -> None:
    rows = result.get("rows")
    if not isinstance(rows, list) or not rows:
        raise AssertionError(f"{label}: expected non-empty rows, got {rows!r}")
    if not isinstance(rows[0], dict):
        raise AssertionError(f"{label}: first row is not an object")


def assert_field_present(obj: dict[str, Any], path: str, label: str) -> None:
    cur: Any = obj
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            raise AssertionError(f"{label}: missing field {path!r}")
        cur = cur[part]
    if cur is None:
        raise AssertionError(f"{label}: field {path!r} is null")


def assert_not_error(result: dict[str, Any], label: str) -> None:
    if result.get("status") == "error":
        raise AssertionError(f"{label} error: {result.get('message') or result.get('code')}")


def assert_evidence_gap_code(result: dict[str, Any], label: str, code: str) -> None:
    gaps = result.get("evidenceGaps")
    if not isinstance(gaps, list):
        raise AssertionError(f"{label}: expected evidenceGaps list, got {gaps!r}")
    for gap in gaps:
        if isinstance(gap, dict) and gap.get("code") == code:
            return
        if isinstance(gap, str) and code in gap:
            return
    raise AssertionError(f"{label}: expected evidenceGaps to include code {code!r}, got {gaps!r}")


def pick_machine_name(list_result: dict[str, Any]) -> str:
    rows = list_result.get("rows")
    if not isinstance(rows, list) or not rows or not isinstance(rows[0], dict):
        raise AssertionError("pick_machine_name: ListUtilizationMachines returned no rows")
    row = rows[0]
    name = row.get("machineName")
    if not isinstance(name, str) or not name.strip():
        raise AssertionError(
            f"pick_machine_name: first row has no canonical machineName: {row!r}"
        )
    return name.strip()


def _round_number(value: Any, places: int = 2) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return round(float(value), places)


def state_summary_projection(result: dict[str, Any]) -> tuple[tuple[str, float | None, Any], ...]:
    """Canonical per-state tuple for shift-normalization comparison (§6.3)."""
    rows = result.get("rows")
    if not isinstance(rows, list):
        return tuple()
    projected: list[tuple[str, float | None, Any]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        state = row.get("utilizationState")
        if not isinstance(state, str):
            state = "" if state is None else str(state)
        projected.append(
            (
                state,
                _round_number(row.get("percentage"), 2),
                row.get("count"),
            )
        )
    return tuple(sorted(projected))


def utilization_percent(result: dict[str, Any]) -> float | None:
    stats = result.get("stats")
    if not isinstance(stats, dict):
        return None
    return _round_number(stats.get("utilizationPercent"), 2)


def assert_shift_summaries_equivalent(
    results: dict[str, dict[str, Any]],
    *,
    reference: str = "All",
    percent_tolerance: float = 0.01,
) -> None:
    """Require All/any/* to return the same aggregate content, not just row counts."""
    if reference not in results:
        raise AssertionError(f"ShiftID normalization: missing reference shift {reference!r}")
    ref_proj = state_summary_projection(results[reference])
    ref_pct = utilization_percent(results[reference])
    if ref_pct is None:
        raise AssertionError(f"ShiftID normalization: {reference!r} missing stats.utilizationPercent")

    for shift, result in results.items():
        if shift == reference:
            continue
        label = f"ShiftID normalization ({reference!r} vs {shift!r})"
        proj = state_summary_projection(result)
        if proj != ref_proj:
            raise AssertionError(
                f"{label}: per-state projection differs\n"
                f"  {reference}: {ref_proj}\n"
                f"  {shift}: {proj}"
            )
        pct = utilization_percent(result)
        if pct is None:
            raise AssertionError(f"{label}: missing stats.utilizationPercent")
        if abs(pct - ref_pct) > percent_tolerance:
            raise AssertionError(
                f"{label}: stats.utilizationPercent differs ({ref_pct} vs {pct}, "
                f"tolerance {percent_tolerance})"
            )


def smoke_list_utilization_machines(server: str, key: str) -> None:
    result = invoke_service(
        server,
        key,
        "ListUtilizationMachines",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "IncludeEffectiveDates": False,
            "MaxItems": 50,
        },
    )
    assert_not_error(result, "ListUtilizationMachines")
    assert_non_empty_rows(result, "ListUtilizationMachines")
    _positive_number(result.get("returnedRows", 0), "ListUtilizationMachines", "returnedRows")
    for index, row in enumerate(result["rows"]):
        machine_name = row.get("machineName") if isinstance(row, dict) else None
        if not isinstance(machine_name, str) or not machine_name.strip():
            raise AssertionError(
                "ListUtilizationMachines: "
                f"row {index} has no canonical machineName: {row!r}"
            )


def smoke_get_utilization_state_summary(server: str, key: str) -> None:
    result = invoke_service(
        server,
        key,
        "GetUtilizationStateSummary",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "IncludeStats": True,
        },
    )
    assert_not_error(result, "GetUtilizationStateSummary")
    assert_non_empty_rows(result, "GetUtilizationStateSummary")
    assert_field_present(result, "stats.utilizationPercent", "GetUtilizationStateSummary")
    if "utilizationState" not in result["rows"][0]:
        raise AssertionError("GetUtilizationStateSummary: first row missing utilizationState")


def smoke_get_utilization_records(server: str, key: str) -> None:
    result = invoke_service(
        server,
        key,
        "GetUtilizationRecords",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "Limit": 50,
            "Offset": 0,
        },
    )
    assert_not_error(result, "GetUtilizationRecords")
    assert_non_empty_rows(result, "GetUtilizationRecords")
    _positive_number(result.get("returnedRows", 0), "GetUtilizationRecords", "returnedRows")
    if "utilizationState" not in result["rows"][0]:
        raise AssertionError("GetUtilizationRecords: first row missing utilizationState")


def smoke_get_utilization_overview(server: str, key: str) -> None:
    result = invoke_service(
        server,
        key,
        "GetUtilizationOverview",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "IncludeMachineCoverage": True,
            "IncludeStateSummary": True,
            "IncludeEffectiveDates": False,
            "MaxMachines": 50,
        },
    )
    assert_not_error(result, "GetUtilizationOverview")
    assert_field_present(result, "stats.utilizationPercent", "GetUtilizationOverview")
    gaps = result.get("evidenceGaps")
    if not isinstance(gaps, list):
        raise AssertionError("GetUtilizationOverview: evidenceGaps must be a list")
    state_summary = result.get("stateSummary")
    if not isinstance(state_summary, dict):
        raise AssertionError("GetUtilizationOverview: missing stateSummary object")
    rows = state_summary.get("rows")
    if not isinstance(rows, list) or not rows:
        raise AssertionError("GetUtilizationOverview: expected non-empty stateSummary.rows")
    coverage = result.get("machineCoverage")
    if not isinstance(coverage, dict):
        raise AssertionError("GetUtilizationOverview: missing machineCoverage object")
    cov_rows = coverage.get("rows")
    cov_returned = coverage.get("returnedRows")
    if (not isinstance(cov_rows, list) or not cov_rows) and not (
        isinstance(cov_returned, (int, float)) and not isinstance(cov_returned, bool) and cov_returned > 0
    ):
        raise AssertionError(
            "GetUtilizationOverview: machineCoverage must have rows or returnedRows > 0 "
            "(playbook path result.machineCoverage.returnedRows)"
        )


def smoke_shift_id_normalization(server: str, key: str) -> None:
    """§6.3: All, any, and * normalize to the same no-shift aggregate."""
    results: dict[str, dict[str, Any]] = {}
    for shift in ("All", "any", "*"):
        label = f"GetUtilizationStateSummary(ShiftID={shift!r})"
        result = invoke_service(
            server,
            key,
            "GetUtilizationStateSummary",
            {
                "StartDate": START_DATE,
                "EndDate": END_DATE,
                "ShiftID": shift,
                "IncludeStats": True,
            },
        )
        assert_not_error(result, label)
        assert_non_empty_rows(result, label)
        assert_field_present(result, "stats.utilizationPercent", label)
        results[shift] = result
    assert_shift_summaries_equivalent(results)


def smoke_single_machine_scope(server: str, key: str) -> None:
    """§6.3: derive machine from list result; exercise one-machine paths."""
    listing = invoke_service(
        server,
        key,
        "ListUtilizationMachines",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "IncludeEffectiveDates": False,
            "MaxItems": 5,
        },
    )
    assert_not_error(listing, "ListUtilizationMachines(pick machine)")
    machine = pick_machine_name(listing)

    summary = invoke_service(
        server,
        key,
        "GetUtilizationStateSummary",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "Machine": machine,
            "IncludeStats": True,
        },
    )
    assert_not_error(summary, f"GetUtilizationStateSummary(Machine={machine})")
    if summary.get("scope") != "machine":
        raise AssertionError(
            f"GetUtilizationStateSummary(Machine=…): expected scope 'machine', got {summary.get('scope')!r}"
        )
    assert_non_empty_rows(summary, f"GetUtilizationStateSummary(Machine={machine})")
    assert_field_present(summary, "stats.utilizationPercent", f"GetUtilizationStateSummary(Machine={machine})")

    records = invoke_service(
        server,
        key,
        "GetUtilizationRecords",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "Machine": machine,
            "Limit": 50,
            "Offset": 0,
        },
    )
    assert_not_error(records, f"GetUtilizationRecords(Machine={machine})")
    if records.get("scope") != "machine":
        raise AssertionError(
            f"GetUtilizationRecords(Machine=…): expected scope 'machine', got {records.get('scope')!r}"
        )
    assert_non_empty_rows(records, f"GetUtilizationRecords(Machine={machine})")
    _positive_number(records.get("returnedRows", 0), f"GetUtilizationRecords(Machine={machine})", "returnedRows")


def smoke_effective_dates_without_window(server: str, key: str) -> None:
    """§6.3: IncludeEffectiveDates=true without dates falls back with evidence gap."""
    result = invoke_service(
        server,
        key,
        "ListUtilizationMachines",
        {
            "ShiftID": "All",
            "IncludeEffectiveDates": True,
            "MaxItems": 50,
        },
    )
    assert_not_error(result, "ListUtilizationMachines(IncludeEffectiveDates,no dates)")
    assert_non_empty_rows(result, "ListUtilizationMachines(IncludeEffectiveDates,no dates)")
    assert_evidence_gap_code(
        result,
        "ListUtilizationMachines(IncludeEffectiveDates,no dates)",
        "EFFECTIVE_DATES_REQUIRE_START_END",
    )
    if result.get("includeEffectiveDates") is True:
        raise AssertionError(
            "ListUtilizationMachines(IncludeEffectiveDates,no dates): "
            "expected includeEffectiveDates false after base-listing fallback"
        )


def smoke_records_pagination(server: str, key: str) -> None:
    """§6.3: pagination fields produce bounded page output."""
    page_size = 10
    page = invoke_service(
        server,
        key,
        "GetUtilizationRecords",
        {
            "StartDate": START_DATE,
            "EndDate": END_DATE,
            "ShiftID": "All",
            "Limit": page_size,
            "Offset": 0,
        },
    )
    assert_not_error(page, "GetUtilizationRecords(pagination)")
    returned = page.get("returnedRows", 0)
    if isinstance(returned, bool) or not isinstance(returned, (int, float)):
        raise AssertionError(f"GetUtilizationRecords(pagination): bad returnedRows {returned!r}")
    if returned > page_size:
        raise AssertionError(
            f"GetUtilizationRecords(pagination): returnedRows {returned} exceeds Limit {page_size}"
        )
    rows = page.get("rows")
    if not isinstance(rows, list):
        raise AssertionError("GetUtilizationRecords(pagination): rows must be a list")
    if len(rows) != int(returned):
        raise AssertionError(
            f"GetUtilizationRecords(pagination): len(rows)={len(rows)} != returnedRows={returned}"
        )


BASE_SMOKES: tuple[tuple[str, SmokeFn], ...] = (
    ("ListUtilizationMachines", smoke_list_utilization_machines),
    ("GetUtilizationStateSummary", smoke_get_utilization_state_summary),
    ("GetUtilizationRecords", smoke_get_utilization_records),
    ("GetUtilizationOverview", smoke_get_utilization_overview),
)

VARIANT_SMOKES: tuple[tuple[str, SmokeFn], ...] = (
    ("ShiftID normalization (All/any/*)", smoke_shift_id_normalization),
    ("Single-machine scope (derived machine)", smoke_single_machine_scope),
    ("IncludeEffectiveDates fallback (no dates)", smoke_effective_dates_without_window),
    ("GetUtilizationRecords pagination", smoke_records_pagination),
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--require-live",
        action="store_true",
        help="Fail when DEV_SERVER/DEV_KEY are missing instead of skipping.",
    )
    return p.parse_args(argv)


def run_smoke_suite(server: str, key: str, suite: tuple[tuple[str, SmokeFn], ...]) -> list[str]:
    failures: list[str] = []
    for name, fn in suite:
        print(f"\n--- {name} ---")
        try:
            fn(server, key)
            print(f"OK   {name}")
        except (AssertionError, json.JSONDecodeError, KeyError) as exc:
            print(f"FAIL {name}: {exc}", file=sys.stderr)
            failures.append(name)
    return failures


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(ENV_PATH)

    if not has_dev_credentials():
        msg = f"SKIP utilization service smoke — set DEV_SERVER and DEV_KEY in {ENV_PATH}"
        if args.require_live:
            print(msg, file=sys.stderr)
            return 1
        print(msg)
        return 0

    server = os.environ["DEV_SERVER"].strip()
    key = os.environ["DEV_KEY"].strip()
    print(f"thing: {THING_NAME}")
    print(f"window: {START_DATE} .. {END_DATE}")

    failures: list[str] = []
    print("\n=== Base service smokes (§6.3 happy path) ===")
    failures.extend(run_smoke_suite(server, key, BASE_SMOKES))
    print("\n=== Service-variant smokes (§6.3 semantics) ===")
    failures.extend(run_smoke_suite(server, key, VARIANT_SMOKES))

    if failures:
        print(f"\nFAIL utilization service smoke ({len(failures)} check(s))", file=sys.stderr)
        return 1

    total = len(BASE_SMOKES) + len(VARIANT_SMOKES)
    print(f"\nOK   utilization service smoke passed ({total} checks)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
