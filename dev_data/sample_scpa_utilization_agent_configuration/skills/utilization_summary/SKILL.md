---
name: utilization_summary
title: Utilization summary
description: Use when the user asks for utilization records, utilization by state, or utilization percent across all utilization-capable machines for a time range.
skill_meta_version: 1
---

### Purpose

Use this skill when the user asks for overall utilization across machines over a time window.

Treat utilization as evidence from the LLM-friendly utilization tools:

- `get_utilization_state_summary` for utilization-state aggregation, utilization percent, uptime, downtime, total time, and event count
- `get_utilization_records` only when the user explicitly asks for raw utilization event records or individual events

### Required inputs

Resolve a time window before querying.

Use explicit `StartDate` and `EndDate` when the user provides dates or a natural time range. If the user does not provide a time window, ask one brief clarifying question. `ShiftID` is optional and should be passed only when the user explicitly scopes the request by shift.

### Required data route

1. For summary, state, percent, uptime, downtime, or duration questions, call `get_utilization_state_summary` with `StartDate`, `EndDate`, and optional `ShiftID`.

2. For raw event-list questions, call `get_utilization_records` with `StartDate`, `EndDate`, and optional `ShiftID`.

3. Do not decompose the request into the old multi-tool helper chain. The new LLM-friendly tools already wrap the service steps.

4. Interpret utilization states only from returned data. Do not invent state names, durations, percentages, or causes.

### Final answer rule

Answer in this order:

1. requested time window
2. total record/event coverage
3. utilization by state
4. utilization percent and uptime/downtime statistics when present
5. evidence gaps, if any

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
      "id": "utilization_records_all_machines",
      "tool": "get_utilization_records",
      "description": "Read raw utilization records for all utilization-capable machines when the user asks for event-level detail."
    },
    {
      "id": "utilization_state_summary",
      "tool": "get_utilization_state_summary",
      "description": "Read utilization by state, percentages, durations, uptime, downtime, total time, and event counts."
    }
  ]
}
```
