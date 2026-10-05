# Built-in tools: alerts (summary + history) with and without scope

## Why this chapter sits here

Chapters **7–9** configured **taxonomy** and **hierarchy** so the agent can resolve **Things**, **asset types**, and **regional scope**. Before adding **`/tools/extended_tools.json`**, it helps students see what **already ships** in Parler: the **curated built-in** tool surface. Maintainers can cross-check implementation details in **[`docs/agent/AGENT-CONTEXT.md`](../../docs/agent/AGENT-CONTEXT.md)** and the alert design notes in **[`docs/operations/alert-solution.md`](../../docs/operations/alert-solution.md)** in this repository.

This lab uses **alerts** because the tools are stable, read-only, and compose cleanly with **`query_entities_by_taxonomy`** plus hierarchy scope. Use **`hierarchyNodeName`** when the user typed a region/site label; use **`hierarchyNodeId`** when Host Context already provides the page-selected node id. The same composition patterns reappear when you promote the built-in-only skill to a playbook (chapter **12**), author **utilization** extended tools (chapter **13**), configure service-call policies (chapter **14**), and then add an **evidence-heavy utilization skill** (chapter **15**).



## Check built-in tools

Indeed, you can ask how many built-in tools are currently model-facing in the agent.

```
how many model-facing built-in tools do you have?
```



<img src="./__images__//image-20260607224437845.png" alt="image-20260607224437845" style="zoom:50%;" />

```
Please list the names of the model-facing built-in tools.
```

<img src="./__images__//image-20260607224530135.png" alt="image-20260607224530135" style="zoom:50%;" />

Do not memorize the exact number from the screenshot. The model-facing tool list is configuration-dependent (about **22** built-ins when a skill catalog is loaded on the current **`parler-agent` 0.1.224** / **`parler-ui-widget` 0.1.92** course target; the underlying lesson first shipped on 0.1.206+/0.1.89+):

- **`get_agent_skill`** appears only when a skill catalog is loaded.
- **`start_playbook`** appears only when a playbook catalog is loaded.
- Extended tools appear only when `/tools/extended_tools.json` is configured and the individual tool is not marked
  executor-only.

So a clean environment with no skills or playbooks may show one or two fewer tools than a fully configured workshop
environment. That difference is expected.

One more diagnostic trap: some models may mention a tool named **`parallel`** or **`multi_tool_use.parallel`**. That is
not a Parler built-in tool. It is an internal model/orchestration capability leaking into the answer, and you should treat
it as a hallucinated tool name rather than a Parler feature.

## Try to check alert summary

```
For asset AC Folding 01, show the current alert summary: open vs acknowledged, highest severity, and alert names.
```



<img src="./__images__//image-20260531222018565.png" alt="image-20260531222018565" style="zoom:50%;" />



## Try to check alert history in a certain time window

```
For asset AC JetDryer 01, list alert history for today only—time, alert name, severity, and ack state. Keep the result small.
```

<img src="./__images__//image-20260531222233284.png" alt="image-20260531222233284" style="zoom:50%;" />



## You can also apply a hierarchy scope

```
For Stacking Robots in USA only, show alert history for the last 24 hours; focus on e-stop and wrist-temperature alerts if any.
```



<img src="./__images__//image-20260531222657948.png" alt="image-20260531222657948" style="zoom:50%;" />



## Tools to highlight

In the current workshop line, the model-facing built-in surface is roughly in the low twenties and depends on loaded
skills, playbooks, and extended tools. Legacy discovery tools such as `discover_properties`, `discover_services`, and
`get_service_definition` are compatibility execution paths, not the primary teaching path.

| Tool | Use |
|------|-----|
| **`query_alert_summary`** | Current in-memory **alert snapshot** for one or more Things (not durable history). In `parler-agent` 0.1.202+, pass `thingNames[]`; a single Thing is a one-element array. |
| **`query_alert_history`** | **Time-bounded** alert events. Use explicit start/end times or a relative/calendar phrase that resolves to a bounded window; keep result caps small for readable answers. Maintainers can cross-check parsing details in **`docs/agent/time-interpretation.md`**. |
| **`query_entities_by_taxonomy`** | List Things matching **`asset-types.json`** (and related filters). |
| **`resolve_thing`** / taxonomy resolvers | Turn labels into canonical **`THINGNAME`**s before alert calls. |

**Teaching line:** *Summary answers “what is firing **now**?” History answers “what happened in this **interval**?”*

When students inspect raw tool calls, call out this deliberate asymmetry:
`query_alert_summary` uses `thingNames[]` in 0.1.202+, while
`query_alert_history` still uses scalar `thingName`.



---

## Bridge to the next chapters

- When prompts get long or steps **drift**, capture the procedure in **`/skills/`** — **chapter 11**.
- When a skill route becomes stable, promote it into a **playbook** — **chapter 12**.
- Students then see **built-in** limits: no first-class **`GetUtilizationRecords`**-style calls until you register **extended tools** — **chapter 13**.

## Maintainer references

These live in the product documentation of this repository:

- **`docs/operations/alert-solution.md`**
- **`docs/agent/query-construction.md`** (alerts row + **QUERY** composition)
- **`docs/agent/playbook-engine-v1a-tool-allowlist.md`** (read-only tool set context)
