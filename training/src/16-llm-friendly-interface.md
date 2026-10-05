# Design an LLM-friendly interface

## Why this chapter exists

Chapters **13-15** walked through the utilization story through service exposure, policy boundaries, and workflow guidance, while Chapter **12** introduced the playbook promotion pattern on the built-in health-comparison story:

1. **Extended tools** exposed the existing ThingWorx services.
2. **Policies** explained when generic service invocation needs HITL.
3. **Skills** specified the intended utilization workflow.
4. **Playbooks** showed how a stable workflow can be made deterministic.

That progression is useful for implementation planning, but it also reveals a deeper design question:

> If we could redesign the application service interface from scratch, would we still expose the same seven services to the LLM?

The answer is no.

The seven services were designed around a mashup and internal ThingWorx service composition. They are valid application services, but they are not the most friendly interface for an LLM.

An LLM-friendly interface should be designed around **user intent**, **simple parameters**, and **answer-ready evidence**.

---

## The current seven-service shape

The SCPA utilization application currently exposes services like these:

| Service | Main role |
| --- | --- |
| `GetMachineListing` | Return utilization-capable machines. |
| `GetMachineListingWithDates` | Return utilization-capable machines plus effective start/end dates. |
| `GetUtilizationRecords` | Return raw utilization records across machines. |
| `GetUtilizationRecordsByMachine` | Return raw utilization records for one machine. |
| `GetAggregatesByUtilizationState` | Aggregate an input records table by utilization state. |
| `GetStatsForAggregateData` | Compute statistics from an aggregate table. |
| `GetAggregatesByUtilizationStateTimeFence` | Return state aggregation for a time window. |

This is a reasonable service surface for a UI-oriented application. A mashup can call one service, hold the returned table in memory, and pass it into another service.

For an LLM, that shape has three problems.

---

## Problem 1: the LLM should not construct `INFOTABLE` inputs

Several existing services rely on table-shaped inputs:

```text
Machines: RootEntityList
UtilizationRecords: PTCSC.Utilization.UtilizationWithDuration
AggregatedByUtilizationStateData: PTCSC.Utilization.Aggregate
```

Those are not natural LLM parameters. The model can reliably pass:

- strings;
- booleans;
- numbers;
- ISO date/time values;
- short arrays of strings;
- enum-like values.

It should not be responsible for constructing or preserving a ThingWorx **`INFOTABLE`** with the correct DataShape.

The important boundary is **who** constructs the table. The open-ended model should not invent it. A validated
**playbook** on `parler-agent` **0.1.191+** (the feature minimum) may pass rows produced by earlier deterministic nodes into an extended tool
with **`$infotable`** and an explicit DataShape. That is runtime-controlled table handoff, not free-form table generation
by the model.

This was exactly the failure in Chapter **13**:

```json
{
  "Machines": []
}
```

The model intended "all machines." The platform interpreted it as an empty machine selection.

The service contract allowed that ambiguity. An LLM-friendly contract should not.

---

## Problem 2: "all machines" should be explicit

An LLM-friendly interface should never require the model to encode "all machines" through an empty table, a missing parameter, or a special UI selection flag.

Use an explicit scope field instead:

```json
{
  "machineScope": "all"
}
```

When the user names a specific machine, use a selected scope:

```json
{
  "machineScope": "selected",
  "machineNames": [
    "SE.CellFab.Model.Workunit.ORD-JetDryer-01"
  ]
}
```

For hierarchy or asset-type questions, the same pattern can extend naturally:

```json
{
  "machineScope": "hierarchy",
  "hierarchyNodeName": "USA"
}
```

If the scope comes from a Mashup page rather than from user text, prefer the actual system identifier from Host Context:

```json
{
  "machineScope": "hierarchy",
  "hierarchyNodeId": "SE.CellFab.Model.Region.USA"
}
```

```json
{
  "machineScope": "assetType",
  "assetType": "Jet Dryer"
}
```

The important design rule is:

> Scope is a business concept. Encode it as a scalar field, not as a side effect of an empty table.

---

## Problem 3: the output should be answer-ready

The model should not recompute utilization percentages from raw rows when the application already owns that logic.

For aggregate questions, return rows that already include:

- utilization state;
- percentage;
- count;
- sum duration;
- min duration;
- max duration;
- average duration.

For machine coverage questions, return:

- machine count;
- machine name;
- display label or description;
- optional effective start/end dates;
- optional data availability flags.

For raw-record questions, return:

- `totalRows`;
- a bounded page of records;
- a cache or export reference for the full table.

The LLM then performs language work: choosing phrasing, explaining caveats, and formatting the answer. It does not perform application math.

---

## Design principle: LLM-facing services are not mashup services

The application can still keep the original seven services internally. The LLM-facing interface should sit on top as a small semantic layer.

Think of it as an adapter:

```text
LLM-friendly service
  -> validates simple user intent
  -> resolves scope
  -> calls one or more existing ThingWorx services
  -> returns answer-ready evidence
```

The adapter hides fragile implementation details:

- whether the UI Manager or implementation Manager owns a call;
- whether a service requires `RootEntityList`;
- whether a service is a record-driven route or a time-fence route;
- whether an aggregate needs a second statistics service.

The LLM sees the business operation.

The same principle applies to property names. A future **Property Role** layer may let an application declare that
business phrases such as `elbow temperature`, `voltage`, or `line speed` map to specific ThingWorx properties for a
given asset type. Until that exists, do not wait for a new taxonomy before improving the interface. Enrich ThingWorx
property descriptions, and when the authoritative description lives in a DataTable, wrap the dictionary as a bounded
lookup service that returns exact property names and explanations.

---

## Proposed LLM-facing services

If we can redesign the interface, expose **four** LLM-facing services instead of seven raw service steps.

### 1. `ListUtilizationMachines`

Use when the user asks:

```text
Which machines are available for utilization reporting?

List utilization-capable machines with their effective start and end dates in the past 7 days.
```

Suggested input:

```json
{
  "machineScope": "all",
  "machineNames": [],
  "hierarchyNodeName": "",
  "assetType": "",
  "startDate": "2026-06-01T00:00:00Z",
  "endDate": "2026-06-02T00:00:00Z",
  "includeEffectiveDates": true,
  "limit": 200,
  "offset": 0
}
```

Suggested output:

```json
{
  "status": "success",
  "machineScope": "all",
  "machineCount": 172,
  "returnedRows": 172,
  "columns": [
    "name",
    "description",
    "effectiveStartDate",
    "effectiveEndDate"
  ],
  "rows": [
    {
      "name": "SE.CellFab.Model.Workunit.ORD-JetDryer-01",
      "description": "Buerkle Jet Drying Tunnel Aachen 01 Workunit",
      "effectiveStartDate": null,
      "effectiveEndDate": null
    }
  ]
}
```

Internal implementation can call:

```text
GetMachineListing
GetMachineListingWithDates
```

The LLM does not see the `Machines` table handoff.

### 2. `GetUtilizationStateSummary`

Use when the user asks:

```text
show utilization aggregated by utilization state across all machines--percent of time per state plus count, min, max, and average duration over the last 24 hours.
```

Suggested input:

```json
{
  "machineScope": "all",
  "machineNames": [],
  "startDate": "2026-06-01T05:28:59Z",
  "endDate": "2026-06-02T05:28:59Z",
  "shiftId": "",
  "includeStats": true
}
```

Suggested output:

```json
{
  "status": "success",
  "machineScope": "all",
  "machineCount": 172,
  "startDate": "2026-06-01T05:28:59Z",
  "endDate": "2026-06-02T05:28:59Z",
  "rows": [
    {
      "utilizationState": "Running",
      "percentage": 36.21,
      "count": 472,
      "sumDurationSeconds": 312841.27,
      "minDurationSeconds": 47.28,
      "maxDurationSeconds": 9516.71,
      "averageDurationSeconds": 662.80
    }
  ]
}
```

Internal implementation can call:

```text
GetMachineListing
GetAggregatesByUtilizationStateTimeFence
```

or:

```text
GetUtilizationRecords
GetAggregatesByUtilizationState
GetStatsForAggregateData
```

The LLM does not need to know which route was used.

### 3. `GetUtilizationRecords`

Use when the user asks for raw records:

```text
Show raw utilization records for ORD-JetDryer-01 in the past 24 hours.
```

Suggested input:

```json
{
  "machineScope": "selected",
  "machineNames": [
    "SE.CellFab.Model.Workunit.ORD-JetDryer-01"
  ],
  "startDate": "2026-06-01T05:30:59Z",
  "endDate": "2026-06-02T05:30:59Z",
  "shiftId": "",
  "limit": 50,
  "offset": 0
}
```

Suggested output:

```json
{
  "status": "success",
  "machineScope": "selected",
  "machineCount": 1,
  "totalRows": 85,
  "returnedRows": 50,
  "hasMore": true,
  "rows": [
    {
      "equipmentId": "SE.CellFab.Model.Workunit.ORD-JetDryer-01",
      "equipmentDescription": "Buerkle Jet Drying Tunnel Aachen 01 Workunit",
      "eventStart": "2026-06-02T05:30:11.745Z",
      "utilizationState": "Running",
      "reasonGroup": "Running",
      "reason": "Good Production",
      "durationSeconds": 69.74
    }
  ],
  "cacheId": "..."
}
```

This service may internally call `GetUtilizationRecordsByMachine` for selected scope, or `GetUtilizationRecords` for all scope. The LLM sees one tool.

### 4. `GetUtilizationOverview`

Use when the user asks a broad overview question and may need multiple evidence blocks:

```text
Show utilization overview across machines for the past 24 hours, including machine coverage and state breakdown.
```

Suggested input:

```json
{
  "machineScope": "all",
  "startDate": "2026-06-01T05:28:59Z",
  "endDate": "2026-06-02T05:28:59Z",
  "includeMachineCoverage": true,
  "includeEffectiveDates": true,
  "includeStateSummary": true,
  "shiftId": ""
}
```

Suggested output:

```json
{
  "status": "success",
  "machineCoverage": {
    "machineCount": 172,
    "returnedRows": 172
  },
  "effectiveDates": {
    "included": true,
    "rows": []
  },
  "stateSummary": {
    "included": true,
    "rows": []
  },
  "evidenceGaps": []
}
```

This service is intentionally answer-oriented. It is the one-shot service version of the extended-tool, policy, and skill/playbook story from Chapters **13-15**.

---

## Error design: never return silent `null`

An LLM-friendly service should not return a successful `null` result when the evidence path is empty.

Prefer structured status:

```json
{
  "status": "empty",
  "code": "NO_RECORDS_IN_TIME_WINDOW",
  "message": "No utilization records were found for the requested machine scope and time window.",
  "machineScope": "all",
  "machineCount": 172,
  "startDate": "2026-06-01T05:28:59Z",
  "endDate": "2026-06-02T05:28:59Z"
}
```

For invalid input, prefer:

```json
{
  "status": "error",
  "code": "INVALID_MACHINE_SCOPE",
  "message": "machineScope must be one of all, selected, hierarchy, or assetType."
}
```

For the specific Chapter **13** failure, the redesigned interface should make this impossible. There is no raw `Machines` argument for the model to set to `[]`.

If a lower-level wrapper still accepts `Machines`, it should reject empty machine tables explicitly:

```json
{
  "status": "error",
  "code": "EMPTY_MACHINE_SELECTION",
  "message": "Machines=[] means no machines. Use machineScope=all for all utilization-capable machines."
}
```

---

## Naming guidance

Tool names should match user intent, not internal service lineage.

Prefer:

```text
list_utilization_machines
get_utilization_state_summary
get_utilization_records
get_utilization_overview
```

Avoid exposing names that force the model to understand implementation routes:

```text
get_aggregates_by_utilization_state_time_fence
get_stats_for_aggregate_data
get_machine_listing_with_dates
```

Those names are meaningful to developers, but they do not help the model choose reliably.

---

## Final utilization `extended_tools.json` (upload target)

Register the four LLM-friendly tools below on your **`configurationRepository`** at **`/tools/extended_tools.json`**. This is the **post-Ch16 final** manifest for SCPA utilization—not the minimal shape example from Chapter **13** and not the service-aligned catalog assumed in the Chapter **15** first-pass skill.

Canonical reference in the **`parler`** tree: **`dev_data/scpa_utilization/tools/extended_tools.json`**.

```json
{
  "version": 1,
  "tools": [
    {
      "name": "list_utilization_machines",
      "title": "List utilization machines",
      "whenToUse": "Use when the user asks which machines are available for utilization reporting, or when a utilization workflow needs machine coverage before querying records or summaries. For a plain availability/listing question, do not request effective dates. Set IncludeEffectiveDates only when the user explicitly asks for effective date fences and StartDate plus EndDate are available. Set ShiftID only when the user names a concrete shift; do not use All, any, or * for unfiltered queries.",
      "target": {
        "entityName": "SCPA_Utilization_helper",
        "serviceName": "ListUtilizationMachines"
      },
      "hitl": false,
      "playbookSafe": true
    },
    {
      "name": "get_utilization_records",
      "title": "Query utilization records",
      "whenToUse": "Use when the user asks for raw utilization event records over a time range. Machine is optional; omit the Machine argument entirely when querying all utilization-capable machines; do not send an empty string for Machine. Set ShiftID only when the user names a concrete shift; do not use All, any, or * for unfiltered queries. Prefer get_utilization_state_summary for aggregate or percent-by-state questions.",
      "target": {
        "entityName": "SCPA_Utilization_helper",
        "serviceName": "GetUtilizationRecords"
      },
      "hitl": false,
      "playbookSafe": true
    },
    {
      "name": "get_utilization_state_summary",
      "title": "Summarize utilization by state",
      "whenToUse": "Use when the user asks for utilization grouped by state, percentage of time by state, counts, total duration, min/max duration, average duration, uptime, downtime, or state-level utilization comparison. Set ShiftID only when the user names a concrete shift; do not use All, any, or * for unfiltered queries.",
      "target": {
        "entityName": "SCPA_Utilization_helper",
        "serviceName": "GetUtilizationStateSummary"
      },
      "hitl": false,
      "playbookSafe": true
    },
    {
      "name": "get_utilization_overview",
      "title": "Get utilization overview",
      "whenToUse": "Use when the user asks for a broad utilization overview that should include machine coverage and state summary evidence in one result. Set ShiftID only when the user names a concrete shift; do not use All, any, or * for unfiltered queries.",
      "target": {
        "entityName": "SCPA_Utilization_helper",
        "serviceName": "GetUtilizationOverview"
      },
      "hitl": false,
      "playbookSafe": true
    }
  ]
}
```

After upload, run **`RefreshPromptContextCache`** (Chapter **13**) so the runtime loads the new tool surface.

---

## Upgraded utilization skills (post-Ch16)

Replace the Chapter **15** first-pass `utilization_overview` skill with the four-tool routes below. The same pattern applies to **`utilization_summary`** and **`machine_utilization_summary`** in the `parler` reference tree—see **`dev_data/scpa_utilization/skills/`** for full **`SKILL.md`** files and checklist fences.

### `utilization_overview` (final)

```markdown
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

### Required data route

1. For a broad utilization overview, call `get_utilization_overview` with `StartDate`, `EndDate`, and optional `ShiftID`.
2. If the user asks specifically which machines are available, call `list_utilization_machines`.
3. Do not decompose the request into the old multi-tool helper chain from Chapter **15**.
4. Use only returned overview evidence for machine coverage, utilization by state, and evidence gaps.
```

The production checklist uses **`get_utilization_overview`** and **`list_utilization_machines`** only—see the reference **`SKILL.md`** for the full **`parler-task-checklist-v1`** block.

**Skill vs playbook note:** a skill named **`machine_utilization_summary`** and a playbook of the same name may assert **different** tool routes (the skill may call **`get_utilization_records`** for raw-event prompts; the playbook may call **`list_utilization_machines`** for disambiguation). Write eval assertions against the artifact under test.

---

## Relationship to skills and playbooks

An LLM-friendly service interface does not remove the value of skills or playbooks.

It changes what they are responsible for.

| Layer | With raw seven services | With LLM-friendly services |
| --- | --- | --- |
| Extended tools | Expose internal service steps. | Expose user-intent operations. |
| Skill | Must specify fragile service sequencing. | Specifies when to use each semantic operation and how to explain evidence. |
| Playbook | Must explicitly manage table handoff and service payload shape. | Composes already-friendly operations for larger workflows; from `parler-agent` 0.1.191+ it can also bind validated derived rows to `INFOTABLE` parameters with `$infotable` (this specific capability starts at 0.1.190). |

The better the LLM-facing interface, the less emergency guidance the skill needs.

The best design is not:

```text
bad service interface + very clever prompt
```

The best design is:

```text
clear service interface + simple skill + deterministic playbook for mature workflows
```

---

## How to frame this with application teams

When working with a ThingWorx application team, do not ask only:

```text
Which services already exist?
```

Ask:

```text
Which user questions should the agent answer?
What simple parameters does the user naturally provide?
Which intermediate tables should stay inside the application?
Which output rows are ready for an answer?
What should an empty result mean?
```

The SCPA utilization story is a good design example because all layers are visible:

1. The original seven services are legitimate application services.
2. Extended tools can expose them.
3. Skills can guide the model through the workflow.
4. Playbooks can make the workflow deterministic; from feature minimum 0.1.191+ they can also handle more service-orchestration glue without a new Java op for every App workflow.
5. A redesigned LLM-facing interface can remove much of the workflow fragility at the source.

That is the interface design takeaway.

## Workshop exercise

For the four-session workshop, this chapter should be treated as a design exercise rather than only a reading chapter.

Ask each participant or small group to pick one customer-style app workflow and answer:

1. What is the user intent?
2. What simple parameters would the user naturally provide?
3. Which existing services are internal implementation details?
4. Which `INFOTABLE` handoffs should stay inside a wrapper?
5. What rows or fields would make the output answer-ready?
6. What structured empty/error states should replace silent `null`?

The expected output is not Java code. The expected output is a proposed LLM-facing service surface that can later be
implemented as ThingWorx services and registered through `extended_tools.json`.
