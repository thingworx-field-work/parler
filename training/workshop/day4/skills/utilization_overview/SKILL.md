---
name: utilization_overview
title: Utilization overview
description: Use when the user asks for a utilization overview across machines, including available machines, date fences, and state-level aggregation over a time range.
skill_meta_version: 1
---

### Purpose

Use this skill when the user asks for a utilization overview across machines rather than a detailed event listing for one machine.

Treat the overview as evidence from the LLM-friendly utilization tools:

- `get_utilization_overview` for broad machine coverage plus state-summary evidence in one result
- `list_utilization_machines` only when the user asks which machines are available or when a machine selection needs validation

### Required inputs

Resolve a time window before querying.

Use explicit `StartDate` and `EndDate` when the user provides dates or a natural time range. If the user does not provide a time window, ask one brief clarifying question. `ShiftID` is optional and should be passed only when the user explicitly scopes the request by shift.

### Required data route

1. For a broad utilization overview, call `get_utilization_overview` with `StartDate`, `EndDate`, and optional `ShiftID`.

2. If the user asks specifically which machines are available for utilization reporting, call `list_utilization_machines`.

3. Do not decompose the request into the old multi-tool helper chain. The new LLM-friendly overview tool wraps the machine coverage and state-summary steps.

4. Use only returned overview evidence to explain machine coverage, utilization by state, date-window caveats, and evidence gaps.

### Final answer rule

Answer in this order:

1. requested time window
2. machine coverage
3. effective date fence caveats, if returned
4. utilization by state
5. overview conclusion
6. evidence gaps, if any

Use only evidence from tool results.

```parler-task-checklist-v1
{
  "schemaVersion": 1,
  "requiredEvidence": [
    {
      "id": "time_window_resolution",
      "description": "Resolve StartDate and EndDate, or ask for the time window."
    },
    {
      "id": "list_utilization_machines",
      "tool": "list_utilization_machines",
      "description": "Read the utilization-capable machine list when the user asks for available machines or scope validation."
    },
    {
      "id": "utilization_overview",
      "tool": "get_utilization_overview",
      "description": "Read machine coverage and utilization-state summary evidence for the requested time range."
    }
  ]
}
```
