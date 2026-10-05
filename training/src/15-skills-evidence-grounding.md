# Extended-tool skills and evidence-grounded answers

This chapter is optional in the four-session workshop if time is short. The first skill in Chapter **11** stays
built-in-only so students learn the core mechanism without also learning SCPA utilization wrappers. Chapter **12** shows
how a stable skill route can become a playbook. Chapter **13** then exposes application-specific tools. Chapter **14**
explains the policy/HITL boundary around generic service invocation. This chapter revisits skills after those extended
tools and policy concepts exist.

> **Pre-LLM-friendly first pass (before Chapter 16):** The utilization skill example below uses **service-aligned** extended tool names from the Chapter **13** classification exercise. It teaches workflow and evidence thinking over a mashup-era tool surface. It is **not** the final configuration students deploy. Chapter **16** publishes the four LLM-friendly tools and the upgraded utilization skills that replace these routes.

## From ad-hoc prompts to skills

Chapter **10** used **free-form prompts** over **built-ins**. Chapter **11** introduced composing those built-ins into a small **skill**. Chapter **12** introduced playbooks for stable built-in workflows. Chapter **13** adds **extended tools** for services Parler does not ship, and Chapter **14** explains when generic service calls require HITL. That is necessary, but it is not enough.

Extended tools define **what callable services exist**. They do not, by themselves, define the **business workflow** behind those services.

Authors still see drift:

- the model finds the right tool name but passes the wrong parameter shape;
- the model skips a required upstream step;
- the model remembers a fact from a previous answer, but does not reuse the upstream table as tool input;
- the final answer sounds reasonable even though the evidence path is empty.

A **skill** is repository-backed guidance: **`/skills/<SkillId>/SKILL.md`** on the **`configurationRepository`**. The runtime exposes **skill metadata** to the prompt assembler; the model still issues normal **`tool_call`** traffic unless a playbook short-circuits the plan.

In other words:

| Layer | Primary responsibility |
| --- | --- |
| Extended tool | "This ThingWorx service is callable." |
| Skill | "For this kind of business question, call these tools in this order and ground the answer in their output." |
| Playbook | "For this repeatable workflow, the runtime executes the steps deterministically." |

This chapter uses the two failed prompts from Chapter **13**, **Sample 3** and **Sample 4**, as the motivation for an
optional utilization overview skill and then for evidence-grounded answer rules.

---

## The two failures from Chapter 13

Before the failures, the user had already asked:

```text
Which machines are available for utilization reporting?
```

The agent correctly called **`utilization_machine_listing`** and found **172** machines. That answer is useful to the user, but it did not automatically become a reusable **`RootEntityList`** input for later tools.

That distinction matters. A natural-language answer such as "there are 172 machines" is not the same thing as an **`INFOTABLE`** with **`RootEntityList`** shape.

### Sample 3: aggregate by state across all machines

The user asked:

```text
show utilization aggregated by utilization state across all machines--percent of time per state plus count, min, max, and average duration over the last 24 hours.
```

The model found the intended tool, but called it like this:

```json
{
  "tool": "utilization_aggregate_by_state_time_fence",
  "arguments": {
    "Machines": [],
    "ShiftID": "",
    "relativeDuration": "24h"
  }
}
```

The platform returned:

```json
{
  "status": "success",
  "result": null,
  "resultKind": "null"
}
```

The problem was not "no utilization data." The problem was **`Machines: []`**.

For the SCPA utilization services, an empty **`Machines`** table means an empty machine selection. It does **not** mean "all machines." When the same service was invoked with the machine list from **`GetMachineListing(UsesSelection=false)`**, it returned the expected aggregate rows: **Unavailable**, **Running**, **Setup**, **Idle**, and **Down**, with percentages, counts, min, max, and average duration.

### Sample 4: effective start and end dates

The user then asked:

```text
List utilization-capable machines with their effective start and end dates in the past 7 days
```

The model again skipped the upstream listing step:

```json
{
  "tool": "utilization_machine_listing_with_dates",
  "arguments": {
    "Machines": [],
    "ShiftID": "",
    "relativeDuration": "7d"
  }
}
```

The service again returned **`null`**, and the final answer incorrectly said there were no utilization-capable machines with effective dates.

The two failures have the same root cause: the agent needs domain workflow guidance.

---

## What the utilization overview skill must specify

The skill should specify a small, concrete rule:

> For an all-machine utilization overview, first obtain the utilization-capable machine list. Then pass that machine list to tools that need **`Machines`**. Never treat **`Machines: []`** as "all machines."

That rule is not a generic LLM behavior. It is specific to the SCPA utilization service contract.

The skill should cover prompts like:

```text
Which machines are available for utilization reporting?

show utilization aggregated by utilization state across all machines--percent of time per state plus count, min, max, and average duration over the last 24 hours.

List utilization-capable machines with their effective start and end dates in the past 7 days
```

It should also clarify the evidence route:

1. Resolve the requested time window.
2. Call **`utilization_machine_listing`** with **`UsesSelection: false`** unless the user supplied a machine subset.
3. For effective-date questions, call **`utilization_machine_listing_with_dates`** using the machine list from step 2.
4. For overview aggregate questions, call **`utilization_aggregate_by_state_time_fence`** using the machine list from step 2.
5. Build the final answer only from tool output.

This is still a **skill**, not a playbook. The model still chooses tool calls. The skill simply makes the correct route explicit in the prompt context.

---

## Example `SKILL.md` (pre-LLM-friendly first pass)

> **Historical exercise only.** Tool names in this example match the service-aligned catalog from Chapter **13**. After Chapter **16**, use the upgraded skill in that chapter instead.

```markdown
---
name: utilization_overview
title: Utilization overview
description: Use when the user asks for utilization overview across machines, including available machines, effective start/end dates, or utilization-state aggregation over a time range.
skill_meta_version: 1
---

### Purpose

Use this skill when the user asks for a utilization overview across machines rather than a detailed raw event listing for one machine.

Treat the overview as evidence from:

- utilization-capable machine listing
- effective start/end dates for those machines, when requested
- utilization-state aggregation for the requested time window, when requested

### Required inputs

Resolve a time window before querying. Use explicit `StartDate` and `EndDate` when the user provides dates or a natural time range.

If the user says "past 24 hours", use `relativeDuration: "24h"` only when the tool schema exposes that field; otherwise use resolved `StartDate` and `EndDate`.

`ShiftID` is optional. Pass it only when the user explicitly scopes the request by shift.

### Required data route

1. Call `utilization_machine_listing` with `UsesSelection: false` unless the user already supplied a specific machine selection.

2. Treat the returned table as the machine set. Do not pass `Machines: []` to downstream utilization overview tools. An empty `Machines` table means empty selection, not all machines.

3. For effective start/end date questions, call `utilization_machine_listing_with_dates` with the resolved time window and the machine list from step 1.

4. For all-machine aggregate-by-state questions, call `utilization_aggregate_by_state_time_fence` with the resolved time window and the machine list from step 1.

5. Use only returned rows as evidence. If a required tool returns empty results, explain which step returned empty and do not infer values.

### Final answer rule

Answer in this order:

1. requested time window
2. machine coverage
3. effective date caveats, if requested and returned
4. utilization by state, if requested and returned
5. evidence gaps, if any
```

The most important sentence is:

> Do not pass **`Machines: []`** to downstream utilization overview tools. An empty **`Machines`** table means empty selection, not all machines.

That one rule explains both failures from Chapter **13**.

---

## Evidence grounding: what changed after the first-pass skill

> **Pre-Ch16 workshop trace.** The expected traces below assume the service-aligned tool catalog from Chapter **13**. Chapter **16** replaces these routes with four LLM-friendly tools; the evidence-grounding *rules* still apply.

In the live workshop, introduce this section only after students have compared a few skill outputs. The variance is the
teaching moment: a skill can improve the route, but it does not automatically make every final claim auditable.

After adding the skill and refreshing the prompt context cache, re-run the same prompts.

For **Sample 3**, the expected trace is:

```text
user:
show utilization aggregated by utilization state across all machines...

assistant:
utilization_machine_listing({ "UsesSelection": false })

tool:
172 machine rows

assistant:
utilization_aggregate_by_state_time_fence({
  "Machines": <machine listing table>,
  "relativeDuration": "24h"
})

tool:
aggregate rows by UtilizationState
```

The final answer should now include values such as state, percentage, count, min duration, max duration, and average duration, because those values exist in the aggregate tool output.

<img src="./__images__//image-20260615182211767.png" alt="image-20260615182211767" style="zoom:80%;" />

For **Sample 4**, the expected trace is:

```text
user:
List utilization-capable machines with their effective start and end dates in the past 7 days

assistant:
utilization_machine_listing({ "UsesSelection": false })

tool:
172 machine rows

assistant:
utilization_machine_listing_with_dates({
  "Machines": <machine listing table>,
  "relativeDuration": "7d"
})
```

The final answer should describe the returned date-fence rows. If a row has no effective start or end date, the answer should say that based on the table. It should not claim that there are no machines unless the listing step itself returned no machines.

<img src="./__images__//image-20260615182706788.png" alt="image-20260615182706788" style="zoom:80%;" />

---

## Why not solve this only with memory?

The model had already answered "Which machines are available for utilization reporting?" before Sample **3** and Sample **4**. However, that earlier answer was natural-language text plus cached tool output. The model did not automatically bind that cached **`RootEntityList`** table into later **`Machines`** parameters.

That is expected. A language model may remember that "172 machines were found," but a later platform service needs a typed **`INFOTABLE`**, not a remembered sentence.

A skill improves the situation by directing the model to either:

- reuse an available compatible machine-list table when the runtime exposes one, or
- call **`utilization_machine_listing`** again before the downstream step.

When reliability must be deterministic, promote the same workflow to a playbook using the Chapter **12** pattern.

### Grounding hardening for long conversations

Conversation history is context, not current evidence. The 2026-08-25 SCPA investigation demonstrated the
failure mode: an earlier alert narrative can be linguistically plausible enough to contaminate a later regional
answer even when the current turn reports zero alerts for that Thing. Write skill rules that survive long threads:

1. Pin every current-alert claim to the current turn's `query_alert_summary.byThing[].thingName` plus a child
   `topAlerts[]` row. Preserve `alertName` and `sourceProperty`; never inherit the affected Thing, timestamp, or
   narrative from an earlier answer.
2. A current property value is never proof that an alert exists. Say “value read; no current alert evidence” when
   that is the actual evidence combination.
3. Reuse property names from earlier tool results only when they belong to the same resolved asset type. Otherwise
   call `discover_properties` on a representative current Thing.
4. Report each `ok:false` property row—especially `PROPERTY_METADATA_UNRESOLVED`—with its Thing and property in
   Evidence Gaps. One successful sibling read does not fill another row's gap.
5. Treat taxonomy `criticalProperties` as projected identity/context columns, not an automatic health checklist.

Use a fresh conversation as a diagnostic control when investigating contamination, but do not make “start a fresh
chat” the product fix. The skill itself must remain grounded when history is long.

---

## Preparing for deterministic execution

### Application semantic profiles and evidence vocabulary (0.1.214)

For application-owned analytics, a semantic profile binds a stable role id to an exact ThingWorx Property or
governed Service, with unit/dimension, grain, cadence, and taxonomy-backed identity facts. The profile is validated
configuration, not prose the model may reinterpret and not a general ontology.

Tool/cache sources carry typed evidence status and completeness into task state. Final-answer safeguards can block
or correct contradictions before delivery; typed recovery categories and actions prevent a cache miss, permission
limit, empty success, partial result, and invalid input from collapsing into the same narrative. A skill should
preserve those meanings instead of replacing them with “tool failed” or “everything is healthy.”

### From skill to deterministic playbook

The same two samples are also the natural bridge to playbooks.

The skill path is:

```text
Specify the workflow in prompt context.
```

The playbook path is:

```text
Let the runtime execute the workflow.
```

For utilization overview, a playbook can encode the table handoff directly:

```text
machine_listing
  -> machine_listing_with_dates
  -> aggregate_by_state_time_fence
  -> final_summary
```

The important difference is that the **`Machines`** table no longer has to be reconstructed by the model. The playbook runtime can pass it from one node to the next as server-side evidence.

This gives a clean implementation progression:

1. Chapter **11**: compose **built-in-only** skills (procedure + evidence checklist).
2. Chapter **12**: promote one stable built-in workflow into a playbook.
3. Chapter **13**: extended tools expose the seven utilization services.
4. Chapter **14**: policies explain generic `invoke_service` HITL behavior.
5. Chapter **15** (this chapter): first-pass skills specify a **service-aligned** utilization workflow and evidence rules.
6. Chapter **16**: an LLM-friendly interface and **final** four-tool catalog replace much of the fragile sequencing from the tool surface itself.

---

## Continue in Chapter 16

Do **not** treat the first-pass `utilization_overview` skill above as the production upload after you complete the LLM-friendly interface lesson.

Chapter **16** publishes:

- the **final** four-tool **`extended_tools.json`** manifest (`list_utilization_machines`, `get_utilization_records`, `get_utilization_state_summary`, `get_utilization_overview`);
- **upgraded** utilization **`SKILL.md`** examples aligned with the `parler` reference tree (`dev_data/scpa_utilization/skills/`).

---

## Further reading

- **`docs/agent/skill-management.md`**
- **`docs/agent/playbook-engine.md`** §1 (contrast **Skill** vs **Playbook** vs **Tool**)
