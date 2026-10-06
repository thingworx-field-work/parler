# Extended tools: `extended_tools.json` and service wrappers

**Prerequisites:** Chapters **7–10** established **taxonomy**, **hierarchy**, and you exercised **built-in** tools (alerts). Chapter **11** composes built-in flows into a **skill**, and Chapter **12** promotes a stable built-in workflow into a **playbook**. **Utilization** and other domain services on **`PTCSC.*`** or helper Things are **not** in that built-in set—you register them here.

## Symptom

A user asks the agent something like:

> Show **utilization** for the last month.

Built-in tools do not call your **CellFab / SCPA** utilization services. You expose them as **extended tools**: JSON entries in **`/tools/extended_tools.json`** on the **`configurationRepository`**, each pointing at a real ThingWorx **`serviceName`** on a concrete **`entityName`**. Merge rules and authoring constraints live in **`docs/agent/configuration-repository.md`**.

Example utilization prompt:

```text
Today: utilization by state for all machines—time share (%) per state plus count, min, max, and average duration.
```

Without extended tools (or with wrong shapes), the model has no supported path to those services.
> Without extended tools, in this scenario, the model can still get to a result with the above prompt. But that will require a lot of calls and user approvals, and the result is less likely to be correct or complete. Doing so anyway would be expensive, especially if the model needs to call a generic `invoke_service` multiple times to discover and fetch data, while it's a repeatable pattern that we can solve with a well-designed extended tool interface and clear guidance in `whenToUse`.

<img src="./__images__//image-20260610164427178.png" alt="image-20260610164427178" style="zoom:80%;" />

<img src="./__images__//image-20260610164427185.png" alt="image-20260610164427185" style="zoom:80%;" />

## What an extended tool is

An extended tool is a **declarative registration**. The Java agent merges the array from **`extended_tools.json`** with built-in tools. Each entry supplies at least:

- A stable **`name`** / human **`title`**
- **`whenToUse`** guidance for the model
- A **`target`** with **`entityName`** and **`serviceName`**
- Optional flags such as **`hitl`** and **`playbookSafe`**

The ThingWorx service signature (parameter names and **`baseType`** values) must match what the platform expects after any resolver or preflight steps described below.

## Wrapper patterns

Many domain services were written for **mashups**, not for **LLM tool calling**. When the original signature does not fit Parler’s tool layer, add a **thin wrapper service** on a Thing **you** control (for example **`SCPA_Utilization_helper`**) and point **`extended_tools.json`** at the wrapper.

### `INFOTABLE` parameters

If a tool argument is **`INFOTABLE`**, the call path must carry a proper **`dataShape`** (or embedded field definitions). The model cannot reliably synthesize arbitrary infotable payloads. Design rule: **prefer wrappers** that accept **scalars** (and, where needed, **handles** to server-side cache or prior playbook steps) and **build the `INFOTABLE` in service code** before delegating to the app developer’s service.

For deterministic playbooks, the feature minimum is **`parler-agent` 0.1.191+** (the specific `$infotable`
capability starts at **0.1.190+**), there is one additional option: the playbook runtime can bind derived row arrays to an extended-tool `INFOTABLE` argument with **`$infotable`** and an explicit `dataShapeName`. That does **not** mean the open-ended model should invent tables in chat. It means a validated DAG can pass rows produced by earlier nodes into a service call without asking the LLM to manufacture the payload.

### Date and time ranges

For natural **date range** pairs exposed to the model, declare **`DATETIME`** parameters named exactly:

- **`startDate`** and **`endDate`**, or
- **`startTime`** and **`endTime`**

The agent’s **`CustomToolDateTimePairResolver`** augments descriptions and can fold **`calendarPhrase`** / **`relativeDuration`** into those canonical fields before invocation; resolved values are written using the **service-declared casing** (for example `StartDate` / `EndDate` on the wire). See **`parler-agent/CHANGELOG.md`** (`CustomToolDateTimePairResolver`) and **`docs/agent/time-interpretation.md`**.

### Thing targets

If a parameter means **which Thing** is acted on, declare it as **`THINGNAME`**, not an unconstrained string. The runtime runs **`THINGNAME` preflight** (`ExtendedToolThingnamePreflight`) and returns structured hints such as **`IDENTITY_RESOLUTION_REQUIRED`** when the model passes a label instead of a canonical name—this ties back to chapters **7–8**.

### Property dictionaries and cryptic property names

Some applications also have a property dictionary: business terms, display labels, units, or descriptions for properties whose ThingWorx names are hard to read. This is different from **identity taxonomy**:

- identity taxonomy resolves **which Thing** the user means;
- property metadata helps the model decide **which property** on that Thing the user means.

Parler does **not** currently ship a first-class **Property Role** resolver or a `/semantics/property-roles.json` catalog. That idea is on the roadmap as part of the broader application semantic layer, but it was deliberately postponed.

The practical reason is that recent testing showed a simpler path often works well enough: when property names and descriptions are available, the model can usually map plain English to real property names by semantic similarity. For example, a user phrase such as:

```text
Elbow temperature
```

can often be mapped to a property such as:

```text
temperature_Elbow
```

without a separate property-role taxonomy.

If that mapping fails, the first fix is usually to improve the **property Description** metadata in ThingWorx. Built-in schema/member discovery can expose those descriptions to the model, so enriched descriptions help immediately.

If the authoritative dictionary lives in a **DataTable**, avoid letting the model raw-query the table and guess joins. Use one of these two patterns instead:

1. **Sync the dictionary into property metadata** when the data is stable enough. This keeps the built-in discovery path simple.
2. **Expose a bounded dictionary lookup as an extended tool** when the dictionary must remain in a DataTable. The wrapper should return answer-ready rows such as `propertyName`, `displayLabel`, `description`, `unit`, and optional aliases.

The important design rule is the same as for utilization services: expose the business capability the model needs, not the internal storage layout.

---

## Case study: SCPA utilization—seven services, design shape, and “orthogonality”

The SCPA utilization slice is documented for maintainers in the **`parler`** tree as **`dev_data/sample_scpa_utilization_design/utilization_service.md`** (service catalog, workflows, and test-window notes). That `dev_data` path is not a workshop artifact. This chapter shows the **service inventory** (prose) and a **minimal `extended_tools.json` shape example**; the **final** four-tool utilization manifest is in **Chapter 16**.

### Service inventory (conceptual roles)

**`PTCSC.UtilizationTWImpl.Manager`**

| Service | Role |
| --- | --- |
| `GetUtilizationRecords` | Raw utilization events (**`UtilizationWithDuration`**) for **all** entities implementing **`PTCSC.Utilization.ModelLogic_TS`** in `[StartDate, EndDate]`, with boundary clipping. |
| `GetUtilizationRecordsByMachine` | Same output shape, **one** machine (`Thing` name). |
| `GetAggregatesByUtilizationState` | Consumes **`UtilizationRecords`** (**`INFOTABLE`**) → **`Aggregate`** by utilization state. |
| `GetStatsForAggregateData` | Consumes **`AggregatedByUtilizationStateData`** (**`INFOTABLE`**) → single-row **`Statistics`**. |

**`PTCSC.UtilizationUI.Manager`**

| Service | Role |
| --- | --- |
| `GetMachineListing` | Machines implementing the model shape; optional input list / selection flags; capped by configuration. |
| `GetMachineListingWithDates` | Same listing logic **plus** per-row effective dates for a window—feeds overview-style collections. |
| `GetAggregatesByUtilizationStateTimeFence` | **`Aggregate`** by state for **`[StartDate, EndDate]`** **without** requiring the caller to pass raw records first. |

### Are the seven APIs orthogonal?

**Not in a strict mathematical sense.** They form **two pipelines** and a few **variants** of the same idea:

1. **Raw events** — `GetUtilizationRecords` and `GetUtilizationRecordsByMachine` share the **same output DataShape** and **the same boundary semantics**; they differ only by an optional **machine filter**. That is one capability with a **single extra dimension**, not two unrelated domains.

2. **Aggregation** — `GetAggregatesByUtilizationState` takes **records you already fetched**; `GetAggregatesByUtilizationStateTimeFence` produces the **same aggregate shape** from **only** a time window on the **UI** Manager. So the platform offers **two routes** to “aggregate by state”: **record-driven** vs **time-fence overview**.

3. **Statistics** — `GetStatsForAggregateData` is a **pure pipe**: **`Aggregate` → `Statistics`**. It does not care whether aggregates came from the record chain or from the time-fence service.

4. **Machine lists** — `GetMachineListingWithDates` is explicitly “**same listing logic** as `GetMachineListing`” with **additional date columns**—again a **one-dimensional extension**, not a separate product feature.

So the **domain** is really about four ideas: **list machines**, **fetch events (optional machine)**, **aggregate by state (with two entry paths)**, **derive headline stats from aggregates**. The **seven** names reflect **how the app split ThingWorx services**, not seven independent “axes” of behavior.

### Could we expose fewer than seven tools?

You **can** reduce the number of **tool definitions** the model sees by **merging** registrations (for example one “records” tool with an optional machine parameter, or one “machine listing” tool with optional date range). That is a **catalog design** choice: fewer names versus **simpler schemas** and **clearer `whenToUse`** text.

You can also push multi-step flows into **playbooks** (introduced in chapter **12**) so the model calls **`start_playbook`** and the **DAG** invokes several services in order. That reduces **tool cardinality at the LLM** without removing platform services.

From feature minimum **0.1.191+** (with these service-orchestration primitives starting at **0.1.190+**), this is stronger than simple sequencing: a playbook can normalize a list of resolved Things, extract scalar values from tool envelopes, build nested service payloads, stringify JSON parameters, derive time windows, and pass derived rows into `INFOTABLE` parameters. Older runtimes need wrapper-service workarounds for many of those shapes.

For **this chapter’s** scope, those consolidations are optional advanced topics. The next subsection records the reference choice used for the utilization walkthrough.

### Reference choice for this chapter (pre-LLM-friendly)

For **Day 3 / Chapter 13**, the goal is to understand **wrapper patterns** and why mashup-era service shapes fail in tool calling—not to ship the final utilization catalog.

- The **seven ThingWorx services** in the inventory table above are the **underlying application surface** (prose inventory only).
- A historical workshop pattern registered **one extended tool per service** (seven model-facing tool names). That layout is useful as a **classification exercise** and for the failure demos below—it is **not** the final upload target students carry into production.
- **Chapter 16** derives four **LLM-friendly** tools and publishes the **final** `extended_tools.json` students should upload for utilization (`list_utilization_machines`, `get_utilization_records`, `get_utilization_state_summary`, `get_utilization_overview`). The canonical manifest lives in the **`parler`** reference tree at `dev_data/sample_scpa_utilization_agent_configuration/tools/extended_tools.json`.

The subsections below on direct vs wrapper wiring and the sample prompts assume a **service-aligned** tool catalog for classroom trace reading. When you implement for real, skip the seven-tool upload and use Chapter 16.

### Which services are wired directly vs through a wrapper?

> Note that the following drives the creation of a **helper Thing** with wrapper services, not the number of tools. The design rule is: **if the original service signature is not LLM-friendly, add a wrapper** that shapes it for the model and keeps the contract stable even if the underlying app service evolves. Even some mashup UI helper services may be not LLM-friendly. For instance, if an Infotable parameter does not include a Datashape, the LLM will reject the call due to security concerns. That means here that the `SCPA_Utilization_helper` entity ***is to be created***.


| # | Application service | Typical `entityName` | Source `entityName` | Wrapper? | Why |
| --- | --- | --- | --- | --- | --- |
| 1 | `GetUtilizationRecords` | `PTCSC.UtilizationTWImpl.Manager` | `PTCSC.UtilizationTWImpl.Manager` | **No** | Scalar window: **`StartDate` / `EndDate`** (paired with **`startDate` / `endDate`** at the tool layer). No **`INFOTABLE`** from the model. |
| 2 | `GetUtilizationRecordsByMachine` | Helper (e.g. `SCPA_Utilization_helper`) | `PTCSC.UtilizationTWImpl.Manager` | **Yes** | **`THINGNAME`** machine plus window; mashup-oriented naming and validation are easier to stabilize on a Thing you own. |
| 3 | `GetAggregatesByUtilizationState` | Helper | `PTCSC.UtilizationTWImpl.Manager` | **Yes** | Upstream **`UtilizationRecords`** is an **`INFOTABLE`**; avoid exposing raw infotable construction to the model—wrapper builds or re-fetches as needed. |
| 4 | `GetStatsForAggregateData` | Helper | `PTCSC.UtilizationTWImpl.Manager` | **Yes** | Input is **`INFOTABLE`** aggregate output; same reasoning as row 3. |
| 5 | `GetMachineListing` | Helper | `PTCSC.UtilizationUI.Manager` -> `PTCSC.UtilizationTWImpl.Manager` | **Yes** | Optional list / selection flags are easier to keep consistent and documented on a helper; keeps one place for project-specific defaults. |
| 6 | `GetMachineListingWithDates` | Helper | `PTCSC.UtilizationUI.Manager` -> `PTCSC.UtilizationTWImpl.Manager` | **Yes** | Combines listing with a datetime window; helper aligns parameter names with resolver / playbook conventions. |
| 7 | `GetAggregatesByUtilizationStateTimeFence` | `PTCSC.UtilizationUI.Manager` | `PTCSC.UtilizationUI.Manager` | **No** | Overview path: scalar **`StartDate` / `EndDate`** only—no prior **`INFOTABLE`** from the caller. |

**Summary:** in the reference layout, **two** tools target **`PTCSC.*` Managers** directly; **five** go through a **helper** so **`INFOTABLE`**-heavy or ergonomically awkward signatures do not leak fragile contracts to the model. Your own deployment may adjust which calls are direct if your platform signatures already satisfy **`THINGNAME`**, **`DATETIME`** pairing, and infotable rules without a helper—**the decision rule is the wrapper patterns above**, not the literal count “five vs two.”

### Wrapper Service example

The wrapper-facing `GetUtilizationRecordsByMachine` service should expose only the parameters the model needs:

* `StartDate`
* `EndDate`
* `ShiftID`: optional; include it only when the user scopes by shift
* `Machine`: declare this as `THINGNAME` in the wrapper signature

The application Manager route may internally accept a less helpful type, but the wrapper is the LLM-facing contract. Parler agent has a built-in preflight: it can validate whether a value is a canonical `THINGNAME`, but it only works when the `baseType` of an input parameter is `THINGNAME`. So, the wrapper service is simple:

<img src="./__images__//image-20260602011506973.png" alt="image-20260602011506973" style="zoom:50%;" />

But the implementation can be kept.

<img src="./__images__//image-20260602011543786.png" alt="image-20260602011543786" style="zoom:50%;" />



### `extended_tools.json` shape example (minimal)

The exact tool count for your application is a **catalog design** choice (see Chapter 16 for utilization). This chapter teaches the **file shape** with a **minimal** manifest—one direct registration and one wrapper registration—using a **generic lab-readings example** so no utilization model-facing tool names appear in pre-Ch16 JSON.

> **Not the final upload target.** Do not treat this snippet as a workshop utilization catalog. Upload the four-tool manifest from Chapter 16 when you reach the LLM-friendly interface lesson.

```json
{
  "version": 1,
  "tools": [
    {
      "name": "query_lab_readings",
      "title": "Query lab readings (direct Manager — shape example)",
      "whenToUse": "Use when the user asks for scalar lab readings across all devices over a time window.",
      "target": {
        "entityName": "SCPA_Lab_Manager",
        "serviceName": "GetReadings"
      },
      "hitl": false,
      "playbookSafe": true
    },
    {
      "name": "query_lab_readings_by_device",
      "title": "Query lab readings by device (wrapper — shape example)",
      "whenToUse": "Use when the user asks for readings for one device; Device must be THINGNAME.",
      "target": {
        "entityName": "SCPA_Lab_helper",
        "serviceName": "GetReadingsByDevice"
      },
      "hitl": false,
      "playbookSafe": true
    }
  ]
}
```

Each real deployment adds more entries (or fewer, after interface design). Flags:

- **`hitl: false`** — read-only query services in this exercise need no approval dialog when invoked as extended tools.
- **`playbookSafe: true`** — read-only services safe for deterministic playbook `tool_call` nodes (Chapter 12).

Upload **`extended_tools.json`** to the configuration repository under **`/tools/`** when you run a hands-on registration exercise:

<img src="./__images__//image-20260602011940213.png" alt="image-20260602011940213" style="zoom:50%;" />



Call `RefreshPromptContextCache` to load the new extended tools into runtime context.

<img src="./__images__//image-20260602012429519.png" alt="image-20260602012429519" style="zoom:50%;" />



**`Caution`**: this refresh service may take 1 minute.

<img src="./__images__//image-20260602012527852.png" alt="image-20260602012527852" style="zoom:50%;" />

### Sample prompts (pre-LLM-friendly failure demos)

The traces below assume a **full service-aligned tool catalog** was registered for classroom exploration (seven tools—one per service). That catalog is **legacy relative to Chapter 16**; keep it for understanding mashup-shaped failures, not as the production upload target.

With extended tools loaded for the exercise, run the following prompts:

#### Sample 1

```
Which machines are available for utilization reporting?
```

<img src="./__images__//image-20260602012808693.png" alt="image-20260602012808693" style="zoom:50%;" />



#### Sample 2

```
Show raw utilization records for ORD-JetDryer-01 in the past 24 hours
```

<img src="./__images__//image-20260602013347100.png" alt="image-20260602013347100" style="zoom:50%;" />

Depending on the LLM model, you may get some additional information, such as "Key Observations" in the below screenshot, obtained with Sonnet 4.6:

<img src="./__images__//image-20260611104326231.png" alt="image-20260611104326231" style="zoom:80%;" />

#### Sample 3

```
show utilization aggregated by utilization state across all machines—percent of time per state plus count, min, max, and average duration over the last 24 hours.
```

<img src="./__images__//image-20260602014328584.png" alt="image-20260602014328584" style="zoom:50%;" />

Why? Check `AgentMessageStream`.

<img src="./__images__//image-20260602015024383.png" alt="image-20260602015024383" style="zoom:50%;" />

```
05:28:54 user:
show utilization aggregated by utilization state across all machines...

05:28:59 assistant tool call:
utilization_aggregate_by_state_time_fence({
  "Machines": [],
  "ShiftID": "",
  "relativeDuration": "24h"
})

05:29:00 tool result:
{"status":"success","result":null,"resultKind":"null"}
```

The tool requires a machine list as input and filters from that list. The LLM found the right tool, but did not provide the right parameter.

Another model can also guess how to retrieve the machines and call the `utilization_machine_listing` tool first, then pass the result to `utilization_aggregate_by_state_time_fence`, after confirmation. In the following screenshot, Sonnet 4.6 did that, with an intermediary failure due to token restrictions, but the cache was kept and used for the final answer.

<img src="./__images__//image-20260611110423289.png" alt="image-20260611110423289" style="zoom:80%;" />

<img src="./__images__//image-20260611110824703.png" alt="image-20260611110824703" style="zoom:60%;" />

<img src="./__images__//image-20260611110943570.png" alt="image-20260611110943570" style="zoom:60%;" />



### Sample 4

```
List utilization-capable machines with their effective start and end dates in the past 7 days
```

<img src="./__images__//image-20260602014016585.png" alt="image-20260602014016585" style="zoom:50%;" />

Why did the answer say `no utilization-capable machine`? Check the stream again:

```
05:30:02 user prompt
05:30:04 assistant -> utilization_machine_listing_with_dates(
  Machines=[],
  ShiftID="",
  relativeDuration="7d"
)
05:30:04 tool -> {"status":"success","result":null,"resultKind":"null"}
05:30:23 assistant -> No utilization-capable machines...
```

This is the same problem as above.

---
With another model (Sonnet 4.6), we observe a different behavior. The model will ask for several approvals to call services and try to discover the machines. But at the end it finally fails too, with a less explicit feedback:

```text
I was able to retrieve the full list of 172 utilization-capable machines, however the GetMachineListingWithDates service is returning a null result regardless of the parameters passed (both with empty ShiftID and ShiftID="1").
This appears to be a platform-side service issue — the service exists and accepts the correct parameters (StartDate, EndDate, ShiftID, Machines) but returns no data.
```

Finally, it is just listing the utilization-capable machines. This is illustrated in the following screenshots:

<img src="./__images__//image-20260611112751664.png" alt="image-20260611112751664" style="zoom:80%;" />

<img src="./__images__//image-20260611112801977.png" alt="image-20260611112801977" style="zoom:80%;" />

<img src="./__images__//image-20260611112815479.png" alt="image-20260611112815479" style="zoom:80%;" />

<img src="./__images__//image-20260611112828559.png" alt="image-20260611112828559" style="zoom:80%;" />

<img src="./__images__//image-20260611112841658.png" alt="image-20260611112841658" style="zoom:80%;" />


### Capability metadata on the current baseline

After designing the wrapper's LLM-friendly Service shape, describe its governed capability in the same
`extended_tools.json` entry. For a read-only query, declare `risk:READ_ONLY`, a stable `purpose`, truthful
dry-run/idempotency support, an admission mode, and availability. Do not claim `dryRun.supported:true` or
`CALLER_KEY` unless the named parameter exists on the live ServiceDefinition.

These fields are runtime policy, not decorative documentation: mutating capability entries require HITL and are
not current Playbook targets; destructive/admin entries are blocked; disabled/off entries are not advertised.
ThingWorx permissions remain the outer authorization boundary. Appendix I is the field-level syntax reference.

### Relationship to chapters 12, 14, 15, and 16

- **Chapter 12 (playbooks):** Fixed **DAG**s reduce **sequencing errors**; they compose tools rather than replacing the need to register them.
- **Chapter 14 (policies and HITL):** Generic **`invoke_service`** is strict by default; explicit policy rules decide which known service calls bypass approval.
- **Chapter 15 (skills and evidence):** Natural-language workflow guidance and evidence rules—**not** a substitute for correct **`extended_tools.json`** types.
- **Chapter 16 (LLM-friendly interface):** Redesign the application surface around user intent; publish the **final** four-tool utilization manifest and upgraded skill/playbook examples.

---
