---
name: machine_utilization_summary
title: Machine utilization summary
description: Use when the user asks for utilization records, utilization by state, or utilization percent for one specific machine over a time range.
skill_meta_version: 1
---

### Purpose

Use this skill when the user asks for utilization of one machine or equipment item over a time window.

Treat utilization as evidence from the LLM-friendly utilization tools:

- `list_utilization_machines` when a display label or partial machine name needs disambiguation
- `get_utilization_state_summary` for utilization-state aggregation, utilization percent, uptime, downtime, total time, and event count for one machine
- `get_utilization_records` only when the user explicitly asks for raw utilization event records or individual state changes

### Required inputs

Resolve these inputs before querying:

1. target machine
2. time window
3. optional shift

Only a non-empty exact Thing name may be passed directly as `Machine`. Treat an equipment identifier, display label, or partial machine name as a lookup hint: first call `list_utilization_machines`, match the returned rows by normalized `machineName` / `displayName` / `description`, and pass the unique row's non-empty `machineName` downstream. If zero or multiple rows match, or the matched row has no `machineName`, ask the user to choose. Do not guess.

Resolve `StartDate` and `EndDate` from the user request. If the user does not provide a time window, ask one brief clarifying question. `ShiftID` is optional and should be passed only when the user explicitly scopes the request by shift.

### Required data route

1. Resolve the target machine to a single machine identifier accepted by the utilization service.

2. For summary, state, percent, uptime, downtime, or duration questions, call `get_utilization_state_summary` with `StartDate`, `EndDate`, optional `ShiftID`, and `Machine`.

3. For raw event-list questions, call `get_utilization_records` with `StartDate`, `EndDate`, optional `ShiftID`, and `Machine`.

4. Do not decompose the request into the old multi-tool helper chain. The new LLM-friendly tools already wrap the service steps.

5. Interpret utilization states only from returned data. Do not invent state names, durations, percentages, or causes.

### Final answer rule

Answer in this order:

1. resolved machine and requested time window
2. total record/event coverage
3. utilization by state
4. utilization percent and uptime/downtime statistics when present
5. notable concentrations or gaps in utilization states
6. evidence gaps, if any

Use only evidence from tool results.

```parler-task-checklist-v1
{
  "schemaVersion": 1,
  "requiredEvidence": [
    {
      "id": "machine_resolution",
      "description": "Resolve the user-supplied machine label to one machine identifier accepted by the utilization services."
    },
    {
      "id": "time_window_resolution",
      "description": "Resolve StartDate and EndDate, or ask for the time window."
    },
    {
      "id": "utilization_records_one_machine",
      "tool": "get_utilization_records",
      "description": "Read raw utilization records for the resolved machine when the user asks for event-level detail."
    },
    {
      "id": "machine_utilization_state_summary",
      "tool": "get_utilization_state_summary",
      "description": "Read utilization by state, percentages, durations, uptime, downtime, total time, and event counts for the resolved machine."
    }
  ]
}
```
