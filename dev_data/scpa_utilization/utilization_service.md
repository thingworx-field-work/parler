# SCPA Utilization services

This document captures the Utilization service surface provided by the ThingWorx application developer.
It is written as plain Markdown so it can be reviewed, versioned, and later consumed by skills, playbooks,
or taxonomy / semantic-layer tooling without depending on the original Excel sheet.

Source context:

- Primary implementation manager: `PTCSC.UtilizationTWImpl.Manager`
- UI overview manager: `PTCSC.UtilizationUI.Manager`
- Utilization model shape: `PTCSC.Utilization.ModelLogic_TS`

## Service catalog

### `PTCSC.UtilizationTWImpl.Manager`

| Service | Inputs | Output DataShape | Description |
| --- | --- | --- | --- |
| `GetUtilizationRecords` | `StartDate`, `EndDate` | `PTCSC.Utilization.UtilizationWithDuration` | Returns a single infotable with all utilization events within the time frame for all entities that implement `PTCSC.Utilization.ModelLogic_TS`. If an event is already in process at the start of the time frame, its event start is treated as the start of the time frame. If an event is still in process at the end of the time frame, its duration is calculated as ending at the end of the time frame. |
| `GetUtilizationRecordsByMachine` | `StartDate`, `EndDate`, `Machine` (`Thing Name`) | `PTCSC.Utilization.UtilizationWithDuration` | Returns a single infotable with all utilization events within the time frame for the specified entity that implements `PTCSC.Utilization.ModelLogic_TS`. Boundary handling is the same as `GetUtilizationRecords`: events in progress at the start are clipped to the start time, and events in progress at the end are clipped to the end time. |
| `GetAggregatesByUtilizationState` | `UtilizationRecords` (from `GetUtilizationRecords` or `GetUtilizationRecordsByMachine`) | `PTCSC.Utilization.Aggregate` | Aggregates the input utilization records by utilization state. The result includes `SUM`, `MIN`, `MAX`, `AVERAGE`, `COUNT`, and duration percentage for each utilization state. |
| `GetStatsForAggregateData` | `AggregatedByUtilizationStateData` (from `GetAggregatesByUtilizationState`) | `PTCSC.Utilization.Statistics` | Returns a single-record infotable containing aggregate statistics, including utilization percent. |

### `PTCSC.UtilizationUI.Manager`

The screenshot identifies this overview service group as `PTCSC.UtilizationUI.Manager`
with `PTCSC.UtilizationTWImpl.Manager` in parentheses. Treat the UI manager as the entry point
for overview workflows, while the implementation manager owns the lower-level utilization data services.

| Service | Inputs | Output DataShape | Description |
| --- | --- | --- | --- |
| `GetMachineListing` | `Machines`, `UsesSelection` | `RootEntityList` | Returns a list of machines that implement `PTCSC.Utilization.ModelLogic_TS`. The listing is limited by the `UtilizationConfiguration` setting `MaxEntitiesOnOverviewScreen` on `PTCSC.UtilizationTWImpl.Manager`. If a machine list is passed in, the service returns that machine list. |
| `GetMachineListingWithDates` | `StartDate`, `EndDate`, `Machines` | `PTCSC.MachineStartEndDates` | Same machine listing logic as `GetMachineListing`, but the result also includes `start date` and `end date` columns. The result is intended to drive the collection used by overview calculations. |
| `GetAggregatesByUtilizationStateTimeFence` | `StartDate`, `EndDate` | `PTCSC.Utilization.Aggregate` | Returns a single infotable aggregated by utilization state for the requested time fence. The result includes `SUM`, `MIN`, `MAX`, `AVERAGE`, `COUNT`, and duration percentage for the utilization records in the time frame. |

## Common workflows

### Detail workflow: all machines in a time range

1. Call `PTCSC.UtilizationTWImpl.Manager.GetUtilizationRecords(StartDate, EndDate)`.
2. Pass the returned `PTCSC.Utilization.UtilizationWithDuration` infotable to
   `GetAggregatesByUtilizationState(UtilizationRecords)`.
3. Pass the returned `PTCSC.Utilization.Aggregate` infotable to
   `GetStatsForAggregateData(AggregatedByUtilizationStateData)`.

Use this when the user asks for overall utilization records, aggregate utilization by state,
or utilization percent over a time window.

### Detail workflow: one machine in a time range

1. Resolve the target machine Thing name.
2. Call `PTCSC.UtilizationTWImpl.Manager.GetUtilizationRecordsByMachine(StartDate, EndDate, Machine)`.
3. Pass the returned `PTCSC.Utilization.UtilizationWithDuration` infotable to
   `GetAggregatesByUtilizationState(UtilizationRecords)`.
4. Pass the returned `PTCSC.Utilization.Aggregate` infotable to
   `GetStatsForAggregateData(AggregatedByUtilizationStateData)`.

Use this when the user asks for utilization of a specific machine.

### Overview workflow

1. Call `PTCSC.UtilizationUI.Manager.GetMachineListing(Machines, UsesSelection)` when the app needs
   the utilization-capable machine list.
2. Call `PTCSC.UtilizationUI.Manager.GetMachineListingWithDates(StartDate, EndDate, Machines)` when
   each machine row also needs its effective time fence.
3. Call `PTCSC.UtilizationUI.Manager.GetAggregatesByUtilizationStateTimeFence(StartDate, EndDate)`
   for time-fenced overview aggregation by utilization state.

Use this when the user asks for a utilization overview across machines rather than a detailed
record listing for one machine.

## Notes for future agent support

- The services return InfoTables; large outputs should be summarized, paged, or cached rather than copied
  directly into an LLM prompt.
- `StartDate` and `EndDate` should be resolved from the user request. If the user does not provide a time
  window, the calling skill or playbook should define its own default before invoking these services.
- Machine identity resolution is not defined by these services. A skill or playbook should resolve display name,
  serial number, hierarchy node, or Thing name before calling `GetUtilizationRecordsByMachine`.
- Aggregation is intentionally service-driven: do not ask the LLM to recompute utilization percentages from raw
  records when `GetAggregatesByUtilizationState` and `GetStatsForAggregateData` are available.

## Utilization stream data for tests

### Stream

Utilization event data is stored in:

```text
PTCSC.UtilizationTWImpl.Utilization_SM
```

This is the stream queried by the utilization manager services above.

### Source files

The test data was imported from daily Excel workbooks under:

```text
dev_data/scpa_utilization/SCPA_xls/
```

The available source files currently cover:

```text
20250903.xlsx
...
20250926.xlsx
```

Use this range when writing utilization test prompts or playbook/eval fixtures:

```text
2025-09-03 through 2025-09-26
```

For service calls that use an exclusive end timestamp, use:

```text
StartDate = 2025-09-03T00:00:00Z
EndDate   = 2025-09-27T00:00:00Z
```

For smaller tests, prefer one-day or multi-day windows inside that range, for example:

```text
2025-09-15T00:00:00Z to 2025-09-17T00:00:00Z
```

### Source workbook columns

The imported Excel workbooks use these columns:

| Column | Meaning |
| --- | --- |
| `id` | Source row / stream entry identifier in the imported workbook. |
| `source` | Source Thing name for the stream entry. |
| `sourceType` | Source entity type, typically `Thing`. |
| `tags` | Data tags from the imported stream entry, often empty. |
| `timestamp` | Utilization event timestamp. |
| `EquipmentID` | Equipment / machine identifier. In many rows this matches the source Thing name. |
| `UtilizationState` | Utilization state, for example `Running`, `Idle`, `Down`, `Setup`, `Unavailable`. |
| `ReasonGroup` | Higher-level reason group, for example `Running`, `Idle`, `Mechanical`, `Electrical`, `Downtime`, `Setup`, `Unavailable`, `Misc`. |
| `Reason` | Specific reason, for example `Good Production`, `No Material`, `Conveyor Jam`, `Tool Adjustment`, `Electrical Fault`, `Cleaning`, `Scheduled Off`. |
| `OperatorID` | Operator identifier, often empty in the test data. |
| `ShiftID` | Shift identifier, often empty in the test data. |
| `ProductID` | Product identifier, often empty in the test data. |
| `Comment` | Free-text comment, often empty in the test data. |
| `ModifiedAt` | Modification timestamp, often empty in the test data. |
| `ModifiedBy` | Modifier, often empty in the test data. |

### Stream query output shape

`QueryStreamData` on `PTCSC.UtilizationTWImpl.Utilization_SM` returns an InfoTable with these fields:

| Field | Base type | Notes |
| --- | --- | --- |
| `timestamp` | `DATETIME` | Utilization event timestamp. |
| `EquipmentID` | `STRING` | Machine / equipment identifier. |
| `UtilizationState` | `STRING` | State label used for aggregation. |
| `ReasonGroup` | `STRING` | Coarser reason category. |
| `Reason` | `STRING` | Specific reason. |
| `OperatorID` | `STRING` | Often empty. |
| `ShiftID` | `STRING` | Often empty. |
| `ProductID` | `STRING` | Often empty. |
| `Comment` | `STRING` | Often empty. |
| `ModifiedAt` | `DATETIME` | Often omitted from JSON rows when empty. |
| `ModifiedBy` | `STRING` | Often empty. |

Example stream row from `QueryStreamData`:

```json
{
  "timestamp": 1756923188000,
  "EquipmentID": "SE.CellFab.Model.Workunit.ORD-JetDryer-01",
  "UtilizationState": "Running",
  "ReasonGroup": "Running",
  "Reason": "Good Production",
  "OperatorID": "",
  "ShiftID": "",
  "ProductID": "",
  "Comment": "",
  "ModifiedBy": ""
}
```

The timestamp above is milliseconds since epoch; `1756923188000` is `2025-09-03T18:13:08Z`.

### Test guidance

- Use dates inside `2025-09-03` to `2025-09-26`; prompts outside this range may produce empty or misleading utilization results.
- Prefer the utilization manager services instead of querying the stream directly. They handle event duration clipping at the requested start/end boundaries.
- The stream can contain multiple equipment naming families. For SCPA CellFab-specific tests, target or filter machines such as `SE.CellFab.Model.Workunit.*` or resolve the machine list through the utilization services before querying by machine.
- The key aggregation dimension is `UtilizationState`; `ReasonGroup` and `Reason` are useful for explaining why time was spent in a state.

## Suggested skill test prompts

Use these prompts after `/tools/extended_tools.json` has been loaded and the corresponding repository skills are available.

Overall utilization summary:

```text
/utilization_summary Show overall utilization from 2025-09-03 to 2025-09-26.
```

One-machine utilization summary:

```text
/machine_utilization_summary Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.
```

Cross-machine utilization overview:

```text
/utilization_overview Show utilization overview across machines from 2025-09-03 to 2025-09-26.
```

## Suggested playbook test prompts

Use these after the utilization playbooks are available in the Configuration Repository (**`/playbooks/<id>/playbook.json`** directory packages) and the utilization extended tools are loaded (**`docs/agent/playbook-app-tool-workflows.md`**).

Two ways to start the same playbook DAG:

1. **Playbook natural language (free text):** the model calls **`start_playbook`** with structured parameters inferred from the message (same style as **`docs/agent/evals/utilization_v1.yaml`** playbook cases).
2. **Playbook structured slash JSON:** send **`/<playbook_id>`** followed by a **single JSON object** with catalog **`inputSchema`** property names (camelCase). This path is parsed before skill slash routing and does **not** rely on an LLM routing call for parameter extraction (see **`PlaybookSlashParser`** in **`parler-agent`**).

### `utilization_summary`

**Playbook natural language (free text)**

```text
Please run playbook utilization_summary with StartDate 2025-09-03T00:00:00Z and EndDate 2025-09-26T23:59:59Z.
```

**Playbook slash JSON**

```text
/utilization_summary {"startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z"}
```

Optional shift when the platform expects it:

```text
/utilization_summary {"startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z","shiftId":""}
```

### `machine_utilization_summary`

**Playbook natural language (free text)**

```text
Please run playbook machine_utilization_summary with machine SE.CellFab.Model.Workunit.ORD-JetDryer-01,
StartDate 2025-09-03T00:00:00Z, and EndDate 2025-09-26T23:59:59Z.
```

**Playbook slash JSON**

```text
/machine_utilization_summary {"machine":"SE.CellFab.Model.Workunit.ORD-JetDryer-01","startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z"}
```

Optional shift:

```text
/machine_utilization_summary {"machine":"SE.CellFab.Model.Workunit.ORD-JetDryer-01","startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z","shiftId":""}
```

### `utilization_overview`

**Playbook natural language (free text)**

```text
Please run playbook utilization_overview with StartDate 2025-09-03T00:00:00Z and EndDate 2025-09-26T23:59:59Z.
```

**Playbook slash JSON**

```text
/utilization_overview {"startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z"}
```

Optional shift:

```text
/utilization_overview {"startDate":"2025-09-03T00:00:00Z","endDate":"2025-09-26T23:59:59Z","shiftId":""}
```

## Time argument notes

The underlying utilization services use ThingWorx-style parameter names `StartDate` and `EndDate`.
Parler's custom / extended-tool time pair resolver recognizes `startDate` + `endDate` and `startTime` + `endTime`
case-insensitively when both parameters are typed `DATETIME`; it preserves the service-declared casing when it
writes the resolved values. Therefore these services can still use the shared natural-time behavior for extended
tools:

- explicit ISO bounds are sent to the platform as `StartDate` / `EndDate`;
- `calendarPhrase` and `relativeDuration` may be exposed as synthetic LLM-facing alternatives when the tool schema is built;
- synthetic fields are stripped before the ThingWorx service is invoked.

For deterministic tests, prefer explicit dates inside the known imported data range rather than relative durations
ending at the current clock.
