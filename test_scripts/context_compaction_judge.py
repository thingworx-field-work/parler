"""Turn judging helpers for context-compaction eval (§11.3)."""

from __future__ import annotations

from typing import Any

CHART_KINDS = frozenset({"line", "bar", "scatter", "pie"})


def judge_turn(turn: dict[str, Any], evidence: dict[str, Any], golden: dict[str, Any] | None) -> dict[str, Any]:
    if not golden:
        return {"judgmentStatus": "insufficient_evidence", "details": {"reason": "no_golden"}}
    reference = golden.get("reference")
    if not isinstance(reference, dict):
        return {"judgmentStatus": "insufficient_evidence", "details": {"reason": "no_reference_evidence"}}

    terminal = evidence.get("terminal")
    if isinstance(terminal, dict):
        terminal_kind = terminal.get("kind")
    else:
        terminal_kind = terminal
    expected_terminal = golden.get("terminal")
    if expected_terminal and expected_terminal != terminal_kind:
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "terminal_mismatch", "expected": expected_terminal, "actual": terminal_kind},
        }

    chart_ref = reference.get("chart")
    if isinstance(chart_ref, dict):
        chart_verdict = _judge_chart_reference(chart_ref, evidence.get("wireEvents") or [])
        if chart_verdict is not None:
            return chart_verdict

    if golden.get("requireAckBeforeDone"):
        wire_events = evidence.get("wireEvents") or []
        ack_idx = next((i for i, ev in enumerate(wire_events) if ev.get("type") == "ack"), -1)
        done_idx = next((i for i, ev in enumerate(wire_events) if ev.get("type") == "done"), -1)
        if ack_idx < 0 or done_idx < 0 or ack_idx > done_idx:
            return {"judgmentStatus": "fail", "details": {"reason": "ack_done_order"}}

    return {"judgmentStatus": "pass", "details": {"terminal": terminal_kind}}


def _judge_chart_reference(expected: dict[str, Any], wire_events: list[dict[str, Any]]) -> dict[str, Any] | None:
    chart = None
    for ev in wire_events:
        if ev.get("type") == "chart":
            chart = ev.get("chart") or ev
            break
        if ev.get("chart"):
            chart = ev.get("chart")
            break
    if not isinstance(chart, dict):
        return {"judgmentStatus": "fail", "details": {"reason": "missing_chart"}}
    if str(chart.get("kind") or "") not in CHART_KINDS:
        return {"judgmentStatus": "fail", "details": {"reason": "malformed_chart", "field": "kind"}}
    series = chart.get("series")
    if not isinstance(series, list) or not series:
        return {"judgmentStatus": "fail", "details": {"reason": "malformed_chart", "field": "series"}}

    first = series[0] if isinstance(series[0], dict) else {}

    if expected.get("kind") and expected["kind"] != chart.get("kind"):
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "chart_kind_mismatch", "expected": expected["kind"], "actual": chart.get("kind")},
        }
    if expected.get("device") and expected["device"] != chart.get("device"):
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "chart_device_mismatch", "expected": expected["device"], "actual": chart.get("device")},
        }
    if expected.get("utilDate") and expected["utilDate"] != chart.get("utilDate"):
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "chart_date_mismatch", "expected": expected["utilDate"], "actual": chart.get("utilDate")},
        }
    if expected.get("timezone") and expected["timezone"] != chart.get("timezone"):
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "chart_timezone_mismatch", "expected": expected["timezone"], "actual": chart.get("timezone")},
        }

    expected_series = expected.get("series")
    if isinstance(expected_series, list) and expected_series:
        first_expected = expected_series[0] if isinstance(expected_series[0], dict) else {}
        exp_y = first_expected.get("y")
        act_y = first.get("y")
        if isinstance(exp_y, list) and isinstance(act_y, list) and exp_y != act_y:
            return {"judgmentStatus": "fail", "details": {"reason": "chart_series_y_mismatch"}}

    min_series = int(expected.get("seriesLabelsMin") or expected.get("seriesMinCount") or 1)
    labels = first.get("labels") or first.get("x") or []
    label_count = len(labels) if isinstance(labels, list) else 0
    if label_count < min_series:
        return {
            "judgmentStatus": "fail",
            "details": {"reason": "chart_series_too_small", "expectedMin": min_series, "actual": label_count},
        }
    return None


def helper_stability_tuple(report: dict[str, Any]) -> tuple[Any, ...] | None:
    if not isinstance(report, dict):
        return None
    call_count = report.get("callCount")
    known_cost = report.get("knownCostUsd")
    if call_count is None or known_cost is None:
        return None
    return (
        int(call_count),
        str(known_cost),
        _optional_int(report.get("usageCompleteCount")),
        _optional_int(report.get("usagePartialCount")),
        _optional_int(report.get("usageUnavailableCount")),
        _optional_int(report.get("usageInvalidCount")),
    )


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    return int(value)


def helper_subtotals(report: dict[str, Any]) -> tuple[int, float, int, int, int, int] | None:
    stability = helper_stability_tuple(report)
    if stability is None or any(v is None for v in stability[2:]):
        return None
    return (
        stability[0],
        float(stability[1]),
        stability[2],
        stability[3],
        stability[4],
        stability[5],
    )
