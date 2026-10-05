# SCPA Utilization Agent Implementation Guide

> **Historical maintainer note (pre-LLM-friendly):** This guide records the earlier service-aligned seven-tool onboarding path for evolution context; it is not the current Day 4/final upload contract. Use this directory's `tools/extended_tools.json`, `skills/`, `playbooks/`, and `docs/agent/training-stage-configuration-contracts.md` for current configuration; do not copy the old tool names or machine-identity rules into the current bundle.

This document records one complete Parler Agent application-extension workflow. It starts from the Utilization service table provided by a ThingWorx App Developer, then shows how to document the service semantics, define extended tools, author repository skills, prepare playbook samples, and validate the whole path against real test data.

The goal is not only to describe the final files. The important part is the method: how to split responsibilities, how to decide the right abstraction boundary, why to validate with skills first, and how to promote a proven skill workflow into a deterministic playbook.

## 1. Goals and Core Decisions

### 1.1 Business Goal

The SCPA Utilization App already implements a set of ThingWorx services for utilization queries and aggregation. Users should be able to ask Parler Agent questions such as:

```text
Show overall utilization from 2025-09-03 to 2025-09-26.
```

```text
Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.
```

```text
Show utilization overview across machines from 2025-09-03 to 2025-09-26.
```

The Agent should:

- recognize the question as a Utilization-domain request;
- use the services already implemented by the App Developer instead of recomputing business metrics from scratch;
- pass the correct time range;
- chain `InfoTable` results from raw records to aggregate tables to final statistics;
- produce a human-readable final answer about utilization states, duration, utilization percent, uptime, downtime, and evidence gaps.

### 1.2 Technical Goal

This extension is organized into four layers:

1. **Service catalog**
   - Convert the App Developer's Excel / screenshot information into versionable Markdown.
   - File: `dev_data/scpa_utilization/utilization_service.md`

2. **Extended tools**
   - Expose concrete ThingWorx services as LLM-callable tools.
   - File: `dev_data/scpa_utilization/tools/extended_tools.json`

3. **Skills**
   - Use natural-language instructions and checklist evidence requirements to guide the LLM through the right tool sequence.
   - Files:
     - `dev_data/scpa_utilization/skills/utilization_summary/SKILL.md`
     - `dev_data/scpa_utilization/skills/machine_utilization_summary/SKILL.md`
     - `dev_data/scpa_utilization/skills/utilization_overview/SKILL.md`

4. **Playbooks**
   - Capture the validated workflow as deterministic DAG samples.
   - Files:
     - `dev_data/scpa_utilization/playbooks/utilization_summary/playbook.json`
     - `dev_data/scpa_utilization/playbooks/machine_utilization_summary/playbook.json`
     - `dev_data/scpa_utilization/playbooks/utilization_overview/playbook.json`

### 1.3 Why Skills Come Before Playbooks

The order matters.

A skill lets the LLM control the workflow. The model reads the skill body, interprets the user's request, chooses tools, and calls them step by step. This is useful when onboarding a new application domain because it helps expose the real integration questions:

- What should the time parameters look like?
- Does the `Machine` parameter expect a Thing name or an `EquipmentID`?
- Can the returned `rows` from one tool be used as the `InfoTable` input to the next tool?
- Are the aggregate and statistics fields sufficient for a final answer?
- Does the LLM understand the relationship between "aggregate by state" and "stats for aggregate data"?

A playbook lets the runtime control the workflow. Intermediate steps are executed by the DAG, and the LLM is used only for the final summary. This is more stable, faster, and cheaper in LLM rounds and tokens, but only after the workflow is already clear.

The chosen development sequence is:

```text
Service catalog -> Extended tools -> Skills -> Manual tests -> Playbook samples
```

Do not start with playbooks. If service parameters, time interpretation, or `InfoTable` chaining are wrong, debugging through a playbook is harder than debugging through a skill.

## 2. Directory Layout

All materials prepared for the SCPA Utilization App live under:

```text
dev_data/scpa_utilization/
```

Current structure:

```text
dev_data/scpa_utilization/
  utilization_service.md
  tools/
    extended_tools.json
  skills/
    utilization_summary/
      SKILL.md
    machine_utilization_summary/
      SKILL.md
    utilization_overview/
      SKILL.md
  playbooks/
    utilization_summary/playbook.json
    machine_utilization_summary/playbook.json
    utilization_overview/playbook.json
  SCPA_xls/
    20250903.xlsx
    ...
    20250926.xlsx
```

## 3. Start From the App Service Table

### 3.1 Raw Input

The App Developer provided a Utilization service table. The table mainly contains:

- Thing Name
- Service Name
- Inputs
- Output DataShape
- Description

This table already contains most of the information needed to extend the Agent:

- where the callable capabilities live;
- what parameters each service needs;
- what structure each service returns;
- how the services should be chained into workflows.

### 3.2 Convert the Table Into Markdown

The first step is not to write tools or skills immediately. First, convert the table into Markdown:

```text
dev_data/scpa_utilization/utilization_service.md
```

This has several benefits:

- humans can review it;
- it can be committed and versioned;
- it can later be consumed by Conflu or another authoring tool;
- skills and playbooks have a stable source document;
- the semantics are not lost if the original Excel file or screenshot disappears.

The document splits services into two groups.

#### `PTCSC.UtilizationTWImpl.Manager`

This is the implementation manager. It owns detail records and lower-level aggregation.

| Service | Purpose |
| --- | --- |
| `GetUtilizationRecords` | Query utilization event records for all utilization-capable entities over a time range. |
| `GetUtilizationRecordsByMachine` | Query utilization event records for one machine over a time range. |
| `GetAggregatesByUtilizationState` | Aggregate utilization records by `UtilizationState`. |
| `GetStatsForAggregateData` | Compute utilization percent, uptime, downtime, and related statistics from an aggregate table. |

#### `PTCSC.UtilizationUI.Manager`

This is the UI overview manager. It owns the overview-oriented surface.

| Service | Purpose |
| --- | --- |
| `GetMachineListing` | Return the utilization-capable machine list. |
| `GetMachineListingWithDates` | Return machine rows with effective start/end dates. |
| `GetAggregatesByUtilizationStateTimeFence` | Return time-fenced utilization aggregation by state. |

### 3.3 Verify the Services in the Runtime

After documenting the table, verify the real ThingWorx runtime. Do not trust screenshots alone.

Use `.env`:

```text
DEV_SERVER
DEV_KEY
```

Call the REST endpoint:

```text
GET /Thingworx/Things/{ThingName}/ServiceDefinitions/{ServiceName}
```

All seven services were found in the runtime.

Important discovery: the actual `ServiceDefinition` includes an optional `ShiftID` parameter that was not shown in the screenshot.

Examples:

```text
GetUtilizationRecords(StartDate, ShiftID, EndDate)
GetUtilizationRecordsByMachine(StartDate, ShiftID, EndDate, Machine)
GetMachineListingWithDates(StartDate, ShiftID, EndDate, Machines)
GetAggregatesByUtilizationStateTimeFence(StartDate, ShiftID, EndDate)
```

Therefore, later tools and skills must treat `ShiftID` as optional. It should not be ignored just because it was absent from the screenshot.

### 3.4 Inspect DataShapes

Next, inspect the output DataShapes and the DataShapes used by `InfoTable` parameters:

```text
PTCSC.Utilization.UtilizationWithDuration
PTCSC.Utilization.Aggregate
PTCSC.Utilization.Statistics
PTCSC.Utilization.MachineStartEndDates
RootEntityList
```

The goals are:

- confirm that no `PASSWORD` type is involved;
- confirm that `InfoTable` parameters can be represented in extended-tool schemas;
- confirm that returned field names are meaningful enough for the LLM;
- prepare for final answer rules and playbook evidence design.

Key fields:

#### `PTCSC.Utilization.UtilizationWithDuration`

Includes:

```text
EquipmentID
UtilizationState
ReasonGroup
Reason
Duration
DurationString
EventStart
ShiftID
ProductID
OperatorID
Comment
```

This is the raw event record layer.

#### `PTCSC.Utilization.Aggregate`

Includes:

```text
UtilizationState
SUM_Duration
SUM_Duration_Hours
SUM_Duration_Minutes
MIN_Duration
MAX_Duration
AVERAGE_Duration
COUNT_Duration
Percentage
```

This is the state-level aggregate layer.

#### `PTCSC.Utilization.Statistics`

Includes:

```text
UtilizationPercent
DowntimePercent
UptimeHour / UptimeMin / UptimeSec / UptimeString
DowntimeHour / DowntimeMin / DowntimeSec / DowntimeString
TotalTimeHour / TotalTimeMin / TotalTimeSec / TotalTimeString
EventCount
AvgEventTime...
```

This is the final summary-statistics layer.

### 3.5 Identify the Real Test Data Range

The backing stream is:

```text
PTCSC.UtilizationTWImpl.Utilization_SM
```

The source workbooks are under:

```text
dev_data/scpa_utilization/SCPA_xls/
```

Current test data covers:

```text
2025-09-03 through 2025-09-26
```

For a service call that should include that full range, use:

```text
StartDate = 2025-09-03T00:00:00Z
EndDate   = 2025-09-27T00:00:00Z
```

When the user says:

```text
from 2025-09-03 to 2025-09-26
```

the LLM or time resolver should produce start/end bounds that cover this data range.

## 4. Build Extended Tools

### 4.1 Why Extended Tools Are Needed

ThingWorx services are not automatically good LLM tools:

- service names often reflect implementation details;
- service descriptions may be empty;
- a workflow may require several services in sequence;
- HITL policy should be explicit at the tool exposure layer;
- password protection, schema generation, and parameter conversion must go through the Agent runtime consistently.

The seven services were registered as repository-backed extended tools:

```text
dev_data/scpa_utilization/tools/extended_tools.json
```

### 4.2 File Format

Extended tools use this format:

```json
{
  "version": 1,
  "tools": [
    {
      "name": "utilization_records",
      "title": "Utilization records",
      "whenToUse": "...",
      "target": {
        "entityName": "PTCSC.UtilizationTWImpl.Manager",
        "serviceName": "GetUtilizationRecords"
      },
      "hitl": false,
      "playbookSafe": true
    }
  ]
}
```

Field meanings:

| Field | Meaning |
| --- | --- |
| `name` | LLM-visible tool name. Keep it short, stable, and space-free. |
| `title` | Human-readable title. |
| `whenToUse` | LLM-facing routing guide. It tells the model when to use the tool. |
| `target.entityName` | The concrete Thing that executes the service. |
| `target.serviceName` | The concrete ThingWorx service to execute. |
| `hitl` | Whether human approval is required. These are read-only query/aggregation services, so this is explicitly `false`. |
| `playbookSafe` | Whether the Playbook runtime may call the tool directly. These are read-only query/aggregation tools, so this is explicitly `true`. |

### 4.3 Naming Principles

Tool names used here:

```text
utilization_records
utilization_records_by_machine
utilization_aggregate_by_state
utilization_stats_for_aggregate
utilization_machine_listing
utilization_machine_listing_with_dates
utilization_aggregate_by_state_time_fence
```

Naming principles:

- use business-oriented names instead of raw ThingWorx naming style;
- keep the name close enough to the underlying service for debugging;
- use underscores for provider function-name compatibility;
- keep one extended tool mapped to one concrete service.

### 4.4 HITL Decision

All seven services are read-only query / aggregation capabilities. Runtime inspection did not find `PASSWORD` parameters or `PASSWORD` return fields. Therefore:

```json
"hitl": false
```

This means:

- the LLM can call these tools directly for utilization questions;
- the user is not interrupted by approval prompts for every read step;
- the generic `invoke_service` HITL / policy behavior is not changed;
- if a future version of a service adds writes or sensitive parameters, this decision must be reviewed again.

### 4.5 Time Parameter Rules

The Utilization services use ThingWorx-style parameter names:

```text
StartDate
EndDate
```

Parler's custom / extended-tool time pair resolver recognizes these parameter pairs case-insensitively:

```text
startDate + endDate
startTime + endTime
```

Therefore `StartDate` / `EndDate` are recognized. The resolver preserves the service-declared casing when writing values back:

```text
StartDate
EndDate
```

Implications:

- explicit ISO timestamps can be passed as `StartDate` / `EndDate`;
- the tool schema may expose synthetic `calendarPhrase` / `relativeDuration`;
- synthetic fields are stripped before the platform service is invoked;
- deterministic tests should still use fixed historical dates, not `last 24h`, because the current test data is from September 2025.

### 4.6 InfoTable Chaining

Two key tools accept `InfoTable` parameters:

```text
utilization_aggregate_by_state(UtilizationRecords)
utilization_stats_for_aggregate(AggregatedByUtilizationStateData)
```

In the skills, this is described as:

```text
Pass the returned rows from utilization_records to utilization_aggregate_by_state.
Pass the returned rows from utilization_aggregate_by_state to utilization_stats_for_aggregate.
```

This does not ask the LLM to recompute results manually. It asks the LLM to pass the previous tool's rows into the next `InfoTable` parameter.

The core pattern is:

```text
raw records -> aggregate by state -> stats
```

In the skill path, the LLM sees tool output and decides how to pass it to the next tool. In the playbook path, intermediate `InfoTable` values should not be serialized into the LLM context and then sent back. The Playbook runtime should retain the raw table inside the JVM and pass it to the next node through `$table`:

```json
{
  "UtilizationRecords": { "$table": "records.result" }
}
```

This avoids replaying large tables through the LLM context and preserves ThingWorx `InfoTable` DataShape metadata.

### 4.7 Helper Thing and Playbook-Safe Target Switching

Manual testing showed that some original Utilization services did not declare enough DataShape metadata on their `INFOTABLE` parameters. For the LLM skill path, the model can often still pass previous `rows` into the next tool. For deterministic playbooks, the runtime must rely on service metadata to decide whether an upstream table can safely be handed to a downstream parameter.

To avoid adding app-specific special cases in the Agent runtime, this case uses an application-side helper Thing:

```text
SCPA_Utilization_helper
```

The helper defines wrapper services with the same service names. Each wrapper delegates to the original Utilization service, but declares the `INFOTABLE` input and output DataShapes explicitly. The extended tool names remain unchanged, so skills and playbooks do not need to know that the underlying service target has moved.

Target mapping after the switch:

| Tool | Target Thing | Service | Reason |
| --- | --- | --- | --- |
| `utilization_records` | `PTCSC.UtilizationTWImpl.Manager` | `GetUtilizationRecords` | Original metadata is sufficient; returns raw records directly. |
| `utilization_records_by_machine` | `PTCSC.UtilizationTWImpl.Manager` | `GetUtilizationRecordsByMachine` | Original metadata is sufficient; returns machine-scoped raw records directly. |
| `utilization_aggregate_by_state` | `SCPA_Utilization_helper` | `GetAggregatesByUtilizationState` | Input `UtilizationRecords` needs explicit `PTCSC.Utilization.UtilizationWithDuration`. |
| `utilization_stats_for_aggregate` | `SCPA_Utilization_helper` | `GetStatsForAggregateData` | Input `AggregatedByUtilizationStateData` needs explicit `PTCSC.Utilization.Aggregate`; output is `PTCSC.Utilization.Statistics`. |
| `utilization_machine_listing` | `SCPA_Utilization_helper` | `GetMachineListing` | Machine-list input/output uses `RootEntityList` for overview workflow chaining. |
| `utilization_machine_listing_with_dates` | `SCPA_Utilization_helper` | `GetMachineListingWithDates` | Input `Machines` uses `RootEntityList`; output is `PTCSC.Utilization.Machine`. |
| `utilization_aggregate_by_state_time_fence` | `PTCSC.UtilizationUI.Manager` | `GetAggregatesByUtilizationStateTimeFence` | Original service aggregates directly by time range and does not depend on upstream `InfoTable` input. |

All seven utilization extended tools are marked:

```json
"playbookSafe": true
```

This marker only means the Playbook runtime may call the read-only tool without HITL. It does not bypass PASSWORD protection and does not change the generic `invoke_service` policy. If a future wrapper adds write behavior or sensitive fields, both `hitl` and `playbookSafe` must be reviewed again.

## 5. Build Skills

This is the most important section. Skills are the first validated delivery surface for this case.

### 5.1 What a Skill Does

A skill is not a tool and not a service implementation. It is business workflow guidance for the LLM.

A good skill answers:

- when to use this workflow;
- what inputs are required;
- what clarification to ask when inputs are incomplete;
- which tool to call first;
- what output from one tool should be passed to the next;
- how to structure the final answer;
- what must not be guessed.

### 5.2 Why Split Into Three Skills

The original service table implies three workflows:

1. all-machine detail summary;
2. one-machine detail summary;
3. cross-machine overview.

They all belong to Utilization, but the user intent and service path are different.

If everything were written as one large `utilization` skill, several problems would appear:

- the LLM would need to choose among multiple paths every time;
- the checklist would become too long;
- later playbook comparison would be unclear;
- slash-command testing would be less convenient.

Therefore the skills are split into:

```text
utilization_summary
machine_utilization_summary
utilization_overview
```

### 5.3 Skill Frontmatter

Each repository skill has frontmatter:

```yaml
---
name: utilization_summary
title: Utilization summary
description: Use when the user asks for utilization records, utilization by state, or utilization percent across all utilization-capable machines for a time range.
skill_meta_version: 1
---
```

The `name` is important:

- it is the slash command;
- the directory name must match the `name`;
- for example, `dev_data/scpa_utilization/skills/utilization_summary/SKILL.md` must declare `name: utilization_summary`.

The `description` is the main entry point for LLM discovery. It should describe the user's intent, not low-level implementation details.

### 5.4 `utilization_summary`

#### Use Case

The user asks for utilization across all machines in a time range:

```text
/utilization_summary Show overall utilization from 2025-09-03 to 2025-09-26.
```

#### Required Input

The time window must be resolved first:

```text
StartDate
EndDate
```

If the user does not provide a time window, the skill tells the LLM to ask a short clarification question. It does not define a default time window because the test data is historical. A default based on the current date would return no useful rows.

`ShiftID` should be passed only when the user explicitly scopes by shift.

#### Tool Route

```text
utilization_records
  -> utilization_aggregate_by_state
    -> utilization_stats_for_aggregate
```

Steps:

1. Call `utilization_records(StartDate, EndDate, ShiftID?)`.
2. Pass the returned `rows` as `UtilizationRecords` into `utilization_aggregate_by_state`.
3. Pass the returned `rows` as `AggregatedByUtilizationStateData` into `utilization_stats_for_aggregate`.

#### Final Answer Rule

The final answer should be ordered as:

1. requested time window;
2. total record / event coverage;
3. utilization by state;
4. utilization percent and uptime/downtime statistics when present;
5. evidence gaps.

This order lets the user confirm the time scope, understand record coverage, inspect state distribution, read final statistics, and see limitations separately.

### 5.5 `machine_utilization_summary`

#### Use Case

The user asks about one machine:

```text
/machine_utilization_summary Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.
```

#### Required Input

Resolve:

1. target machine;
2. time window;
3. optional shift.

Machine identity is the riskiest part of this skill. The service parameter is:

```text
Machine
```

The user may provide:

- a Thing name;
- an `EquipmentID`;
- a display label;
- a partial name.

The skill explicitly says:

- if the user gives an exact Thing name or equipment identifier, use it as `Machine`;
- if the user gives a display label or partial name, call `utilization_machine_listing` first;
- match returned rows by normalized name / description;
- if zero or multiple rows match, ask the user instead of guessing.

#### Tool Route

```text
utilization_records_by_machine
  -> utilization_aggregate_by_state
    -> utilization_stats_for_aggregate
```

This route is similar to the all-machine summary. The first step is machine-scoped.

#### Final Answer Rule

The final answer should be ordered as:

1. resolved machine and requested time window;
2. total record / event coverage;
3. utilization by state;
4. utilization percent and uptime/downtime statistics when present;
5. notable concentrations or gaps in utilization states;
6. evidence gaps.

The answer must mention the resolved machine because identity resolution is the largest risk in this path.

### 5.6 `utilization_overview`

#### Use Case

The user wants an overview rather than raw records or single-machine detail:

```text
/utilization_overview Show utilization overview across machines from 2025-09-03 to 2025-09-26.
```

#### Tool Route

```text
utilization_machine_listing
utilization_machine_listing_with_dates
utilization_aggregate_by_state_time_fence
```

This workflow differs from the first two:

- it does not necessarily need all raw event records;
- the UI manager already provides overview aggregation;
- machine listing and date fences explain coverage;
- time-fenced aggregation explains utilization by state.

#### Why Not Use the Detail Workflow?

The goal of an overview is not to list every utilization event. It is to provide the user's overall view.

If overview also used:

```text
records -> aggregate -> stats
```

it might:

- retrieve too many raw rows;
- use more tokens;
- run slower;
- diverge from the UI App's overview logic.

Therefore the overview skill uses the UI manager's overview services.

### 5.7 Checklist Purpose

Each skill ends with:

```text
parler-task-checklist-v1
```

The checklist is not primarily for the end user. It is used to:

- show intermediate state in the task-state UI;
- discourage the LLM from skipping required evidence;
- support later eval checks on tool-call completeness;
- train skill authors to write evidence routes.

Example:

```json
{
  "id": "utilization_aggregate_by_state",
  "tool": "utilization_aggregate_by_state",
  "description": "Aggregate utilization records by UtilizationState."
}
```

The checklist should not hardcode conclusions. It should describe required evidence.

### 5.8 What Skills Must Avoid

These three skills share several rules:

1. Do not ask the LLM to recompute utilization percent from raw records.
   - The App already provides `GetStatsForAggregateData`.

2. Do not invent utilization states.
   - Use only returned `UtilizationState` values.

3. Do not invent downtime or uptime causes.
   - Explain causes only when `ReasonGroup` / `Reason` evidence is returned.

4. Do not use the current date when the user omitted a time window.
   - The current test data is historical.

5. Do not guess machine identity.
   - Ask the user when matching returns zero or multiple candidates.

## 6. Validate Skills

### 6.1 Loading Order

Before testing the skills, load repository content according to the configuration repository layout:

```text
/tools/extended_tools.json
/skills/utilization_summary/SKILL.md
/skills/machine_utilization_summary/SKILL.md
/skills/utilization_overview/SKILL.md
```

When prepared from `dev_data/scpa_utilization`, the repository-relative structure is normally:

```text
tools/extended_tools.json
skills/<skill_id>/SKILL.md
```

### 6.2 Suggested Test Prompts

Overall:

```text
/utilization_summary Show overall utilization from 2025-09-03 to 2025-09-26.
```

One machine:

```text
/machine_utilization_summary Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.
```

Overview:

```text
/utilization_overview Show utilization overview across machines from 2025-09-03 to 2025-09-26.
```

### 6.3 Acceptance Criteria

For each test, verify:

1. The LLM selects the correct skill.
2. The correct extended tools are called.
3. The time range falls within `2025-09-03` to `2025-09-26`.
4. `StartDate` / `EndDate` are passed correctly to ThingWorx.
5. `InfoTable` rows are passed to the next aggregation tool.
6. The final answer is grounded in tool results.
7. Empty results are described as empty results, not as missing services.

### 6.4 Test Result

All three tests were manually validated successfully.

This proves:

- the seven extended tools can be registered and called;
- `StartDate` / `EndDate` casing does not block time interpretation;
- `ShiftID` can exist as an optional parameter;
- raw records can be chained into aggregate and statistics tools;
- the skill guidance is sufficient for the LLM to choose the correct service route.

## 7. From Skill to Playbook

### 7.1 Why Playbook Work Can Start

Once the skill path is validated, the workflow is stable enough to promote into playbooks.

Playbook goals:

- execute steps automatically in the runtime;
- reduce LLM rounds;
- reduce token cost;
- show more stable task progress in the UI;
- use the LLM only for final summary over compact evidence.

### 7.2 Skill-to-Playbook Mapping

The three skills map to three playbooks:

| Skill | Playbook |
| --- | --- |
| `utilization_summary` | `utilization_summary/playbook.json` |
| `machine_utilization_summary` | `machine_utilization_summary/playbook.json` |
| `utilization_overview` | `utilization_overview/playbook.json` |

The skill's "Required data route" can be translated almost directly into playbook nodes.

Skill route:

```text
utilization_records
  -> utilization_aggregate_by_state
    -> utilization_stats_for_aggregate
```

Playbook node:

```json
{
  "id": "records",
  "kind": "tool_call",
  "tool": "utilization_records"
}
```

Next node:

```json
{
  "id": "aggregate_by_state",
  "kind": "tool_call",
  "dependsOn": ["records"],
  "tool": "utilization_aggregate_by_state",
  "args": {
    "UtilizationRecords": { "$table": "records.result" }
  }
}
```

Next node:

```json
{
  "id": "stats",
  "kind": "tool_call",
  "dependsOn": ["aggregate_by_state"],
  "tool": "utilization_stats_for_aggregate",
  "args": {
    "AggregatedByUtilizationStateData": { "$table": "aggregate_by_state.result" }
  }
}
```

### 7.3 `utilization_summary/playbook.json`

This playbook maps to the all-machine detail workflow.

Nodes:

1. `records`
   - calls `utilization_records`;
   - inputs: `startDate`, `endDate`, optional `shiftId`.

2. `aggregate_by_state`
   - calls `utilization_aggregate_by_state`;
   - input comes from `$table: records.result`.

3. `stats`
   - calls `utilization_stats_for_aggregate`;
   - input comes from `$table: aggregate_by_state.result`.

4. `final_summary`
   - LLM summary;
   - evidence references include records, aggregate, and stats.

### 7.4 `machine_utilization_summary/playbook.json`

This playbook maps to the one-machine detail workflow.

Nodes:

1. `records_by_machine`
   - calls `utilization_records_by_machine`;
   - inputs:
     - `StartDate`;
     - `EndDate`;
     - `ShiftID`;
     - `Machine`.

2. `aggregate_by_state`
   - calls `utilization_aggregate_by_state`;
   - input comes from `$table: records_by_machine.result`.

3. `stats`
   - calls `utilization_stats_for_aggregate`;
   - input comes from `$table: aggregate_by_state.result`.

4. `final_summary`
   - LLM summary.

This playbook assumes `machine` is already resolved into a usable machine identifier. If the user provides only a partial display label, a future deterministic machine resolver or an upstream LLM parameter extraction step is still needed.

### 7.5 `utilization_overview/playbook.json`

This playbook maps to the overview workflow.

Nodes:

1. `machine_listing`
   - calls `utilization_machine_listing`;
   - default `UsesSelection: false`.

2. `machine_listing_with_dates`
   - calls `utilization_machine_listing_with_dates`;
   - inputs:
     - `StartDate`;
     - `EndDate`;
     - `ShiftID`;
     - `Machines` from `$table: machine_listing.result`.

3. `aggregate_by_state_time_fence`
   - calls `utilization_aggregate_by_state_time_fence`;
   - inputs:
     - `StartDate`;
     - `EndDate`;
     - `ShiftID`.

4. `final_summary`
   - LLM summary;
   - evidence references include listing, dates, and aggregate.

### 7.6 Current Playbook Notes

The business DAG represented by these playbooks has already been validated through the skill path. For the playbook path, the main requirement is not to redesign the workflow, but to make sure the runtime can execute the same DAG deterministically:

1. playbook ids are enabled in the registry;
2. playbook `tool_call` nodes can call extended tools;
3. utilization extended tools are explicitly marked `playbookSafe: true`;
4. records -> aggregate -> stats `InfoTable` chaining uses `$table` to pass raw tables inside the JVM;
5. intermediate services that require explicit DataShape metadata are exposed through the `SCPA_Utilization_helper` wrappers, avoiding schema skips or playbook validation failures caused by incomplete original service metadata.

Correct next step:

```text
Use skills to prove the business workflow.
Use helper/wrapper services to complete runtime metadata.
Compare skill and playbook outputs using the same prompts.
```

### 7.7 Playbook Acceptance Criteria

When playbook runtime support exists, verify:

1. `/utilization_summary` completes records -> aggregate -> stats.
2. `/machine_utilization_summary` completes machine-scoped records -> aggregate -> stats.
3. `/utilization_overview` completes machine listing -> dates -> time-fenced aggregate.
4. Tool-call data matches the data obtained through the skill tests.
5. Final response is semantically close to the skill response.
6. LLM round count is lower than the skill route.
7. ApplicationLog has no validation, coercion, password-protection, or table-reference errors.

## 8. Reusable Method

This SCPA Utilization extension can be generalized into an application onboarding method.

### 8.1 Ask the App Developer for a Service Map

Minimum required fields:

- Thing name;
- Service name;
- Inputs;
- Output DataShape;
- short description;
- common workflow order.

Useful additional fields:

- which parameters are identities;
- which parameters are time ranges;
- which services are read-only;
- which services have side effects;
- which DataTables / Streams are the backing data sources;
- test data date range.

### 8.2 Verify the Runtime, Not Only the Document

Use ServiceDefinitions to verify:

- service existence;
- parameter list;
- hidden parameters, such as `ShiftID` in this case;
- BaseTypes;
- `InfoTable` parameter DataShapes;
- result DataShape;
- password-sensitive fields.

### 8.3 Define Atomic Extended Tools First

Do not start by making the whole workflow a black-box tool.

Prefer:

```text
one service -> one extended tool
```

This makes it easier to:

- let the LLM compose tools flexibly;
- validate through skills first;
- reuse the same tools in playbooks later;
- read logs clearly;
- identify which step failed.

### 8.4 Express the Workflow as a Skill

A skill should describe the business route, not platform internals.

Each skill should contain:

- Purpose;
- Required inputs;
- Required data route;
- Final answer rule;
- Checklist.

The data route is the most important part. It tells the LLM:

```text
which evidence to collect first
which result to pass to which tool
how to organize the final answer
```

### 8.5 Test the Skill

Skill testing is not about producing beautiful wording. It is about proving:

- tools can be called;
- parameters can be converted;
- data can be retrieved;
- workflow order is correct;
- final answer is grounded.

### 8.6 Then Write the Playbook

Only after the skill path is stable should a playbook be written.

A playbook is not just another documentation format for a skill. It transfers a stable workflow from LLM-controlled execution to runtime-controlled execution.

Rule of thumb:

| Situation | Preferred Form |
| --- | --- |
| Workflow is still unstable | Skill |
| LLM judgment is needed for next step | Skill |
| Steps are clear and repeated | Playbook |
| One service already performs the whole business capability | Tool |
| Need visible progress with fewer LLM rounds | Playbook |

## 9. Follow-Up Improvements

### 9.1 Utilization-Specific Improvements

Potential next steps:

1. Machine identity resolver
   - Deterministic resolution from display name, partial label, or `EquipmentID` to exact `Machine`.

2. Utilization evidence compactor
   - Structured evidence lines for `PTCSC.Utilization.Aggregate` and `PTCSC.Utilization.Statistics`.
   - Avoid final summaries that see only row counts.

3. Shift-aware prompts
   - Add tests that use `ShiftID`.

4. Overview vs detail routing eval
   - Check whether the LLM chooses the right skill when the user says "overview", "summary", or "records".

5. Empty-result behavior
   - If a date range has no data, answer "no utilization records" instead of implying the service is missing.

### 9.2 Generic Playbook Engine Improvements

To make the Utilization playbooks executable configuration, the Playbook Engine needs:

1. extended tools in playbook `tool_call`;
2. per-tool playbook safety marking for read-only extended tools;
3. `InfoTable` row array or raw table output to `InfoTable` parameter conversion;
4. better evidence extraction for generic `InfoTable` tools;
5. additional catalog ids beyond the current built-in examples.

After these are implemented, Utilization becomes an excellent Playbook Engine validation case because it includes:

- time ranges;
- machine identity;
- raw rows;
- `InfoTable`-to-`InfoTable` chaining;
- aggregation;
- final statistics;
- overview and detail workflows.

## 10. Current Deliverables

Service and data documentation:

```text
dev_data/scpa_utilization/utilization_service.md
```

Extended tools:

```text
dev_data/scpa_utilization/tools/extended_tools.json
```

Skills:

```text
dev_data/scpa_utilization/skills/utilization_summary/SKILL.md
dev_data/scpa_utilization/skills/machine_utilization_summary/SKILL.md
dev_data/scpa_utilization/skills/utilization_overview/SKILL.md
```

Playbook samples:

```text
dev_data/scpa_utilization/playbooks/utilization_summary/playbook.json
dev_data/scpa_utilization/playbooks/machine_utilization_summary/playbook.json
dev_data/scpa_utilization/playbooks/utilization_overview/playbook.json
```

Suggested smoke prompts:

```text
/utilization_summary Show overall utilization from 2025-09-03 to 2025-09-26.
```

```text
/machine_utilization_summary Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.
```

```text
/utilization_overview Show utilization overview across machines from 2025-09-03 to 2025-09-26.
```

## 11. Most Important Lesson

The most reusable lesson from this case is:

```text
Do not push complex application workflows into the system prompt first.
Do not start with a black-box tool.
First expose application services as atomic extended tools.
Then validate the business workflow with skills.
Finally promote the stable workflow into playbooks.
```

This path preserves:

- business logic already implemented by the App Developer;
- the LLM's flexible interpretation and final-language ability;
- the Playbook runtime's deterministic execution;
- traceability for future eval, training, and documentation.
