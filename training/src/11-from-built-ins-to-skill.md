# From built-in tools to a skill

This chapter starts with **no skill** and **no playbook**. We let the user drive a real
multi-turn investigation with ordinary prompts, watch how many built-in-tool steps are needed,
then convert the same repeated route into the Day 2 skill:

```text
workshop/day2/skills/asset_pair_health/SKILL.md
```

The same story is also the basis for the Day 3 playbook:

```text
workshop/day3/playbooks/cross_asset_pair_health/playbook.json
```

The teaching point is deliberately simple:

- built-ins can answer meaningful app questions,
- but a useful answer often needs several turns,
- repeated multi-turn routes are fragile as prompt habit,
- a skill captures the route as repository-backed procedure.

Evidence-grounded syntax is not introduced here. Day 4 revisits the same skill with stricter
evidence rules.

## 1. Starting Point: No Skill, No Playbook

The operator asks a natural business question:

```text
please compare the health status between ORD Contacting 02 and ORD Contacting 01
```

This is intentionally vague. There may be no `healthStatus` property. In the live system, a weak
first answer may say that no direct health property exists. That is acceptable. It creates the
right workshop moment: **health is not a property lookup; health is an assessment built from
evidence.**

There is an important model-behavior lesson here. The screenshots in this chapter use a
`gpt-5.4` deployment, and the first answer may stop after discovering that neither asset has an
obvious health/status property. In a separate test with a Sonnet model, the model was more
aggressive: it inferred that "health" could be approximated by alert evidence and started using
`query_alert_history` by itself.

That Sonnet result is useful, but it is not the design contract. It means one model happened to
guess a better diagnostic route from the available tool names. Another model, or the same model on
another day with a different context, may not. For application developers, this is exactly the
reason to write a skill: do not rely on a model to rediscover your business diagnostic procedure
from a vague word like "health". Capture the intended route in repository-backed guidance.

The rest of the story turns that vague goal into evidence:

- recent alert history,
- current alert summary,
- current alert-driving properties,
- trends for those properties,
- a final ranked health assessment.

## 2. Multi-Turn Built-In-Only Story

Run these turns before uploading the skill. Do not use `/asset_pair_health` yet.

For screenshots, capture both the prompt and the important result area. If the UI shows tool
trace, also capture the tool rows for the turn.

### Turn 1: Try the Natural Question

```text
please compare the health status between ORD Contacting 02 and ORD Contacting 01
```

Expected learning:

- The model may search for a literal health/status property.
- Some models may instead infer the alert-history route by themselves. Treat that as a lucky
  successful route, not as something to depend on.
- A weak answer is useful because it shows why the user should not have to know the diagnostic
  route.
- If the model does resolve Things or read values, check that it uses canonical Thing names after
  resolution.

<img src="./__images__//image-20260607225502285.png" alt="image-20260607225502285" style="zoom:50%;" />

\
> Note: even without skill properly set, some models, like Sonnet 4.6, may still guess a good route and provide a useful answer. This is a testament to the model's reasoning capabilities, but it also highlights the importance of skills for ensuring consistent and reliable behavior across different models and contexts.

### Turn 2: Compare Alert History

```text
How did their alert histories compare over the past 24 hours?
```

Expected route:

- Resolve the two asset labels to canonical Things if not already resolved.
- Use the same 24h window for both assets.
- Call `query_alert_history` for each asset.
- Compare row counts, repeated alert names, state/severity if present, and `sourceProperty`.

<img src="./__images__//image-20260607225731690.png" alt="image-20260607225731690" style="zoom:50%;" />

> Again, some models may have guessed this route in Turn 1 and 2. If so, treat it as a lucky success. The point of the skill is to capture this route as the intended procedure, not to rely on a model to guess it.

### Turn 3: Compare Current Alert Summary

```text
How do their current alert summaries compare?
```

Expected route:

- Call `query_alert_summary` once with both canonical Things in `thingNames[]`
  (`parler-agent` 0.1.202+).
- Treat this as the current alert snapshot, not the same thing as the 24h history.
- Compare active/current alert rows and any available `sourceProperty`, severity, state, or
  description fields.
- Tie every alert claim to the current turn's `byThing[].thingName` and its child `topAlerts[]`
  row. In a long conversation, earlier prose is context, not current alert evidence.

<img src="./__images__//image-20260607225851449.png" alt="image-20260607225851449" style="zoom:50%;" />

> Again, some models may have guessed this route in Turn 3. However, do not to rely on a model to guess it. The skill will capture this as the intended procedure for comparing current alert summaries.

### Turn 4: Identify the Top Alert-Driving Properties

```text
Which two properties generated the most alerts?
```

Expected route:

- Count `sourceProperty` values from the alert-history evidence first.
- Use current alert summary as supporting evidence only.
- Select up to two properties that best explain the comparison.
- Do not invent property names from English labels.

<img src="./__images__//image-20260607225947019.png" alt="image-20260607225947019" style="zoom:50%;" />



### Turn 5: Trend Those Properties

```text
Show the past 24-hour trends for those two properties on both assets as line charts.
```

Expected route:

- Use the exact property names selected in Turn 4.
- Call `query_property_history` for each selected property and each asset with `kind: "line"`
  and a clear title.
- Do not pass aggregate `actions` on this chart-producing call.
- With two assets and two properties, expect up to four history calls and up to four charts.
- On **parler-agent 0.1.205+**, the same-property overlay case may instead use one
  **`build_history_overlay_chart`** call per property (two charts total for two properties).
- If a property is not logged, the answer should say so and continue with available evidence.

<img src="./__images__//image-20260607230120319.png" alt="image-20260607230120319" style="zoom:50%;" />

<img src="./__images__//image-20260607230155434.png" alt="image-20260607230155434" style="zoom:50%;" />

### Turn 6: Compare the Trends Statistically

```text
Statistically compare those trends over the past 24 hours.
```

Expected route:

- Use returned statistics, sample rows, row counts, and chart evidence from Turn 5.
- If the chart-producing calls did not return enough statistics, make a second bounded
  `query_property_history` call with aggregate `actions`.
- Do not replace the chart-producing call with aggregate-only actions.
- Compare mean, median, min, max, first/latest value, direction, spread, or sample-size
  differences when available.
- Do not request or print every point from long history results.

<img src="./__images__//image-20260607230401988.png" alt="image-20260607230401988" style="zoom:50%;" />



### Turn 7: Close the Loop

```text
Based on the above, summarize their health condition.
```

Expected answer shape:

- Most urgent asset.
- Why it is more urgent.
- Likely issue driver.
- Recommended next check.
- Next priority asset.
- Evidence used.
- Limitations.

The answer should not end with "If you want, I can...". It should complete the task.

It must also list failed or unresolved reads for the exact Thing/property under **Limitations**.
A successful read for the other asset does not fill that evidence gap. Taxonomy
`criticalProperties` are projected context fields, not an automatic health checklist and not
proof that an alert exists.

<img src="./__images__//image-20260607230526289.png" alt="image-20260607230526289" style="zoom:50%;" />



## 3. What the Multi-Turn Story Reveals

The user did not ask for a tool chain. The user asked for health.

But a good answer needed a route:

```text
resolve asset / thing identity
  -> query_alert_history
  -> query_alert_summary
  -> rank alert source properties
  -> query_property_history
  -> compare statistics
  -> produce ranked assessment
```

This route is not business trivia. It is application knowledge:

- Health is not a literal property unless the app defines one.
- Alert history and current alert summary answer different questions.
- `sourceProperty` is the bridge from alerts to trend data.
- Trends must use exact ThingWorx property names.
- The same time window must be used across both assets.
- Recommendations must be grounded in returned evidence.

If we leave this as prompt habit, the next run may drift. A skill is the smallest mechanism that
captures this procedure without writing Java code.

## 4. Combine the Route into a Skill

The Day 2 skill is:

```text
workshop/day2/skills/asset_pair_health/SKILL.md
```

It is built-ins only. It does not depend on extended tools and does not use playbook execution.

The skill turns the seven prompts into one reusable procedure:

```text
User asks for health/risk/degradation comparison between two named assets
  -> resolve both complete asset identifiers to canonical Thing names
  -> use asset type only as an explicit narrowing fallback
  -> collect alert history over one shared time window
  -> collect current alert summary
  -> choose up to two alert-driving sourceProperty names
  -> trend those properties for both assets
  -> produce ranked assessment with evidence and limitations
```

### 4.1 Front Matter

The front matter makes the skill discoverable:

```yaml
---
name: asset_pair_health
title: Asset pair health comparison from alerts and trends
description: Use when the user asks to compare the health, condition, degradation, or operational risk of two named assets. Build the answer from alert history, current alert summary, top alert source properties, and property trends over a shared time window.
skill_meta_version: 1
---
```

The important field is `description`. It tells the model when this procedure should be loaded.

### 4.2 Purpose

The skill begins by redefining "health" as evidence:

```text
Health is a conclusion, not the input property name.
```

This directly addresses Turn 1, where the model may have looked for a literal `healthStatus`
property.

### 4.3 Clarify First

The skill requires:

- two asset identifiers or Thing names,
- a time window or permission to use the default,
- an optional asset-type hint only when the user supplied a separate type constraint or ambiguity requires it.

Default:

```text
past 24 hours
```

The goal is to ask one brief clarification question only when necessary.

### 4.4 Resolution Path

The skill freezes the identity route:

```text
resolve_thing for full asset A label
resolve_thing for full asset B label
only then use resolve_asset_type as a narrowing fallback if needed
use canonical Thing names downstream
```

This prevents the model from splitting labels such as `ORD Contacting 02` into a guessed
asset type plus a partial identifier, and it prevents passing display labels or casual names
into later tools.

### 4.5 Evidence Collection

The skill maps directly to the multi-turn story:

```text
query_alert_history       # Turn 2
query_alert_summary       # Turn 3
rank sourceProperty       # Turn 4
discover_thing_members    # only if property names are uncertain
query_property_history    # Turn 5 and Turn 6
```

The skill also says what not to do:

- do not guess property names,
- do not fabricate charts,
- do not reproduce full point lists,
- do not invent thresholds or fault codes.

### 4.6 Answer Shape

The skill makes the final response consistent:

```text
Ranked assessment
Next priority
Overall call
Evidence used
Limitations
```

That answer shape is exactly what Turn 7 asks the model to produce manually.

## 5. Upload, Validate, Refresh

The runtime path is:

```text
/skills/asset_pair_health/SKILL.md
```

Steps:

1. Upload `SKILL.md` to the configured `ConfigurationRepository`.

   <img src="./__images__//image-20260607230944843.png" alt="image-20260607230944843" style="zoom:50%;" />

2. Run `ValidateAgentConfigurationRepository`.

   <img src="./__images__//image-20260607231041019.png" alt="image-20260607231041019" style="zoom:50%;" />

3. Confirm `asset_pair_health` is registered without errors.

4. Run `RefreshPromptContextCache`.

5. Start a clean test turn.



## 6. Test the Skill

Let's cut-off the existing prompts and responses.

<img src="./__images__//image-20260607231220334.png" alt="image-20260607231220334" style="zoom:50%;" />

Use the skill with the same story:

```text
/asset_pair_health please compare the health status between ORD Contacting 02 and ORD Contacting 01 over the past 24 hours
```

Expected live behavior:

- `resolve_thing` is called directly for the two complete asset labels.
- No `assetTypeKey` is passed on the first resolution attempt.
- Alert history and alert summary are read for both assets.
- Trend calls use the chart-capable path first: `query_property_history` with `kind: "line"`
  and no aggregate `actions`.
- The UI should show line charts for the selected trend properties when history data is available.

<img src="./__images__//image-20260608012727364.png" alt="image-20260608012727364" style="zoom:50%;" />

<img src="./__images__//image-20260608012755907.png" alt="image-20260608012755907" style="zoom:50%;" />

<img src="./__images__//image-20260608012823549.png" alt="image-20260608012823549" style="zoom:50%;" />



Now run the same business goal through the skill:

```text
/asset_pair_health Compare ORD Contacting 02 and ORD Contacting 01 over the past 24 hours.
```

Expected behavior:

- The model loads the skill body.
- It no longer treats health as a literal property lookup.
- It follows the alert-history, alert-summary, property-ranking, trend, and ranked-assessment
  route.
- It reports missing data as limitations instead of inventing evidence.

<img src="./__images__//image-20260608013005884.png" alt="image-20260608013005884" style="zoom:50%;" />

<img src="./__images__//image-20260608013036781.png" alt="image-20260608013036781" style="zoom:50%;" />

## 7. How This Prepares Day 3

Day 2 skill:

```text
workshop/day2/skills/asset_pair_health/SKILL.md
```

Day 3 playbook:

```text
workshop/day3/playbooks/cross_asset_pair_health/playbook.json
```

They are the same story at two different levels.

| Day 2 Skill | Day 3 Playbook |
| --- | --- |
| Markdown procedure for the model | Deterministic DAG (Directed Acyclic Graph) executed by the runtime |
| Flexible and easy to author | More stable and inspectable |
| Still depends on model following instructions | Runtime controls node order |
| Best first step for app developers | Best next step for repeated workflows |

The playbook encodes the same route as nodes:

```text
taxonomy_row
resolve_a / resolve_b
normalize_a / normalize_b
pair_assets
alert_history_by_asset
alert_thing_names
alerts_by_pair
alert_groups
current_alert_groups
primary_property
property_union
top_trend_properties
values_by_asset
trend_targets
trends_by_target
trend_summary
pair_summary
final_summary
```

***That is why the story matters. We are not teaching skill and playbook as abstract features. We
are showing how a real multi-turn diagnostic route becomes first a skill, then a playbook.***

## Further Reading

- [Built-in tools: alerts](./10-built-in-tools-alerts.md)
- [Appendix: Built-in tools](./E-built-in-tools.md)
- [Configuration Repository](./C-configuration-repository.md)
- Day 4 skill: `workshop/day4/skills/asset_pair_health/SKILL.md`
