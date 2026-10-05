---
name: asset_pair_health
title: Asset pair health comparison from alerts and trends
description: Use when the user asks to compare the health, condition, degradation, or operational risk of two named assets. Build the answer from alert history, current alert summary, top alert source properties, and property trends over a shared time window.
skill_meta_version: 1
---

### Purpose

Compare the health of exactly **two** application assets by turning the vague business word
**health** into evidence:

1. time-window alert history,
2. current alert summary,
3. current alert-driving properties,
4. property trend evidence for those properties,
5. a ranked operational assessment.

Do **not** look for a literal `health`, `healthStatus`, or `status` property unless the tool
results show that such a property actually exists and is relevant. Health is a conclusion, not
the input property name.

### Clarify first

Proceed if the user supplied:

- two asset identifiers or Thing names,
- a time window, or enough context to use the default.

Default time window: **past 24 hours**.

Ask one brief question only when one of the two assets is missing or ambiguous. Asset type is
optional. If the type phrase is present, use it only as a narrowing hint for resolution.

### Resolution path

1. Resolve both complete asset identifiers first:
   - call `resolve_thing` once per full asset identifier,
   - do **not** pass `assetTypeKey` on the first attempt,
   - keep the canonical Thing `name` from each successful result.

2. Do not split an asset label into type plus identifier. For example, treat
   `ORD Contacting 02` as one asset identifier. Do not infer that `Contacting` is an
   asset-type hint unless the user separately says something like "Contacting assets" or
   "asset type Contacting".

3. Use `resolve_asset_type` only as a narrowing fallback:
   - when the user supplied a separate asset-type phrase, or
   - when `resolve_thing` returned `IDENTITY_AMBIGUOUS` and a type hint would disambiguate.
   Then retry `resolve_thing` with the resolved `assetTypeKey`.

4. If `resolve_thing` remains ambiguous, show the candidates and ask the user to pick.
   If it returns `IDENTITY_NOT_FOUND`, do not guess a Thing name. Ask for a narrower identifier.

5. Use the canonical Thing names for every downstream tool call. Do not pass casual labels,
   display labels, or partial identifiers where a tool expects canonical Thing names.

### Evidence collection

Use the same time window for both assets and for every history/trend comparison.

1. **Alert history**
   - Call `query_alert_history` for each Thing.
   - Use the shared time window, `ackState: "all"` when applicable, and a practical limit.
   - Compare event counts, repeated alert names, alert state/severity when available, and
     `sourceProperty` values.

2. **Current alert summary**
   - On `parler-agent` 0.1.202+, call `query_alert_summary` once with both
     canonical names in `thingNames[]`.
   - Use `ackState: "all"` unless the user specifically asks for unacknowledged alerts.
   - Treat this as the current alert snapshot, not durable history.

3. **Top alert-driving properties**
   - Use current alert summary rows as the strongest current-health signal when they include
     `sourceProperty`.
   - Use time-window alert history to explain recent churn and repeated event patterns.
   - Select up to two properties that best explain the comparison. Prefer properties that are:
     - currently active,
     - frequent in the requested history window,
     - present on both assets,
     - strongly different between the two assets,
     - meaningful for the alert text or severity.
   - If more than two properties tie, choose the two with the clearest operational explanation.
   - If no `sourceProperty` is available, say that property-level trend comparison is not
     supported by the alert evidence and continue with alert-only health assessment.

4. **Property existence check**
   - If a selected property name is uncertain, call `discover_thing_members` with
     `facet: "properties"` on one or both Things before using it.
   - Use exact ThingWorx property names from tool output. Do not convert business labels such as
     "voltage" or "force" into guessed property names.

5. **Trend evidence**
   - For each selected property, first call `query_property_history` for each Thing over the same
     time window using the chart-capable path:
     - pass `kind: "line"`,
     - pass a clear `title`,
     - do **not** pass aggregate `actions` on this chart-producing call.
   - With two assets and two selected properties, expect up to four history calls and up to four
     charts on older agents. On **parler-agent 0.1.205+**, prefer one
     **`build_history_overlay_chart`** call per property (two charts for two properties) when
     overlaying the same property across both assets.
     history data, report that limitation rather than inventing a chart.
   - If the tool returns compact evidence, use the returned statistics, sample rows, row counts,
     chart references, and cache metadata. Do not ask for or reproduce a full point list.
   - If the user also asks for statistical comparison and the chart-producing call does not return
     enough statistics, make a second bounded `query_property_history` call with aggregate
     `actions`. Do not replace the chart-capable trend call with aggregate-only actions when the
     story expects charts.
   - If a property is not logged or cannot be trended, report that limitation and continue with
     the available evidence.

### Statistical comparison

Compare only values supported by tool output. Useful statistics include:

- count of alert events,
- current active alert count,
- mean, median, min, max, latest value, or returned summary statistics from trend tools,
- volatility or spread when directly available or cheaply derived from returned samples,
- trend direction when visible from the returned evidence,
- missing-data or sample-size differences.

Do not invent thresholds, fault codes, root causes, or maintenance recommendations. If alert rows
or current summary include threshold names, fault codes, severity, description, or state flags,
you may use those exact details as evidence.

### Answer shape

Always close the loop. Do not end with "If you want, I can...".

Use this structure:

1. **Ranked assessment**
   - `Most urgent`: canonical Thing name.
   - `Why`: alert count/current alert/trend evidence.
   - `Likely issue driver`: the property or alert family most supported by the evidence.
   - `Recommended next check`: one practical next inspection, grounded in the same evidence.

2. **Next priority**
   - Same fields for the other asset.

3. **Overall call**
   - which asset is less healthy,
   - best health separator,
   - shared watch item if both assets show the same noisy property or alert family.

4. **Evidence used**
   - alert history window,
   - alert summary snapshot,
   - selected top properties,
   - trend/chart evidence that was actually queried.

5. **Limitations**
   - missing `sourceProperty`,
   - unavailable trend data,
   - unequal sample sizes,
   - any unresolved ambiguity.

### Guardrails

- Health is an evidence-backed assessment, not a direct property lookup.
- Use the same time window for both assets.
- Compare canonical Thing names only.
- Prefer current alert-summary `sourceProperty` names for current-health trend selection; use
  alert history as the time-window comparison.
- Keep recommendations operational and modest: "inspect", "check", "verify", "review".
- Never fabricate a chart, statistic, threshold, fault code, or property.
