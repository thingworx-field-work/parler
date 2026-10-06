---
name: asset_pair_health
title: Asset pair health comparison
description: Use when the user asks to compare the current health or recent behavior of two assets of the same asset type by name, display name, serial number, or another identifying property.
skill_meta_version: 1
---

### Purpose

Use this skill when the user asks to compare two individual assets of the same asset type, especially when the assets are identified by Thing name, display name, serial number, or another business identifier.

Treat "health" as evidence from:

- current alert summary rows for both assets
- current values for identity, critical, and alert-related properties
- recent numeric trend history for the main problem property when one can be identified

### Clarification rule

First identify these three inputs from the user request:

1. the asset type
2. the two asset identifiers
3. the time window

If the asset type cannot be inferred from the user request or prior conversation, ask one brief clarifying question for the asset type before querying. Do not scan unrelated platform inventory to guess an asset type.

If the two asset identifiers are ambiguous after lookup, ask the user to choose from the candidate Things. Do not compare the wrong pair.

If the user does not provide a time window, use the past 24 hours. Prefer `relativeDuration: "24h"` for that default. When the user gives a clear relative duration, calendar phrase, or explicit ISO window, pass the matching time arguments supported by the relevant history tool.

### Required data route

1. Resolve the asset type using built-in taxonomy tools — **not** from any hidden prompt table. Call **`resolve_asset_type`** with the user’s asset-type phrase (or **`list_asset_types`** when you need to list or disambiguate). Use the tool result’s **`entityType`**, **`entityName`**, and **`criticalProperties`** (array of property names on the resolved row). For **`query_entities_by_taxonomy`**, join those names into the tool’s **`CriticalProperties`** argument as a single **ASCII semicolon-separated** string (same order as returned is fine).

Treat `criticalProperties` as taxonomy-selected identity/context projection, not as an automatic list of health
dimensions. A projected value never proves that an alert exists.

2. Resolve each asset identifier to exactly one Thing.

Use `query_entities_by_taxonomy` with the taxonomy row from step 1. Include the taxonomy **`CriticalProperties`** string so the result projects identity fields such as display name and serial number when present.

For identifiers that look like business values rather than Thing names, try exact `LookupProperties` against likely identity columns from the taxonomy row, such as display-name, serial-number, asset-id, equipment-id, or tag-id properties. `LookupProperties` is exact-match and OR across keys, so call it separately for each asset identifier.

Exact lookup failure is not a final failure. User-facing asset labels often omit prefixes, punctuation, or separators that appear in Thing names. If exact lookup does not resolve one asset, query the taxonomy row without `LookupProperties` and match the returned candidate rows yourself against:

- `name`
- projected display-name style columns
- projected serial-number style columns
- other projected identity columns from `CriticalProperties`

Use normalized comparison before asking the user for help:

- lowercase both sides
- remove spaces, hyphens, underscores, dots, and other punctuation
- allow a user identifier to match the normalized suffix or token sequence of a full Thing name

Examples of acceptable normalized matches (this skill path only):

- `ORD JetDryer 02` can match `...ORD-JetDryer-02`
- `AC JetDryer 01` can match `...AC-JetDryer-01` (platform `PTCDisplayName` on testbox)
- a serial number can match the projected serial-number column exactly after trimming

**Playbook / structured slash contrast:** `cross_asset_pair_health` passes `assetIdentifierA` / `assetIdentifierB` verbatim into **`resolve_thing`**, which matches `PTCDisplayName` with identity-rule **`equals`** (case-insensitive, no space folding). Structured slash JSON and playbook inputs must use the platform display name — e.g. `AC JetDryer 01`, not `AC Jet Dryer 01`. Natural-language and this skill's taxonomy listing + normalized matching above can tolerate minor spacing differences; slash and playbook paths cannot.

If a hierarchy/site/line scope is present in the user request, pass `hierarchyNodeName` on the taxonomy query.

Do not use `spotlight_search` as the next step for a taxonomy asset after exact lookup fails. First list the candidate rows under the taxonomy asset type and try normalized matching. Only ask the user to choose when normalized matching returns zero or multiple plausible candidates.

If the user clarifies that the identifiers are display names or business labels after a failed lookup, retry the taxonomy candidate listing and normalized matching path. Do not repeat only the same exact `LookupProperties` query.

3. Call **`query_alert_summary` once** with **`thingNames`** set to both resolved canonical Thing names (two-element array). Use `ackState` **`all`** and a practical `limit` such as **`100`**. Read **`byThing[]`** and **`completeness`**.

Pin each alert claim to this current turn's parent `byThing[].thingName` and child `topAlerts[]` row. Never
reuse an alert name, source, timestamp, or affected Thing from narrative in an earlier turn.

4. Identify the main problem property from alert evidence.

Group alert evidence by `sourceProperty` from each **`byThing`** entry's **`topAlerts`**. Prefer a primary property that has current alert evidence on either asset, especially when it explains a difference between the two assets. If the user named a health dimension, map it only to an exact ThingWorx property name from tool evidence; do not pass translated labels or business phrases as property names.

If alert rows do not contain usable `sourceProperty`, use `discover_properties` on one representative resolved Thing to find exact property names that clearly match the user's requested dimension. If no exact property can be resolved, compare alerts and current values only, and state the evidence gap.

5. Read current values.

Call `get_property_values` for both Things with:

- identity / critical properties needed for labels
- the primary problem property, when known
- any additional alert-related `sourceProperty` values needed to explain the comparison

Do not call a property normal only because it lacks an alert. Say "no current alert evidence" unless a current value or trend was read.

Report every `ok:false` property read, including `PROPERTY_METADATA_UNRESOLVED`, as an evidence gap for the
specific Thing and property. A successful read for the other asset does not fill that gap.

6. Show recent trend for the primary problem property when possible.

When the primary problem property is numeric (`NUMBER`, `INTEGER`, or `LONG`), call `query_property_history` for both resolved Things with the same `propertyName` and the same time window.

Use `kind: "line"`. Use clear titles that include the asset label and property name. If alert evidence exposes a clear numeric threshold, pass it as a `y_reference_lines` entry.

Current Parler numeric-history auto-charting emits one chart per `query_property_history` call. Therefore, for v1, expect two side-by-side/sequential charts rather than one combined two-series chart. Do not promise a single combined chart unless a future tool result provides a combined tabular source that can safely drive a multi-series chart.

If the primary problem property is not numeric, **do not expect auto charts**. You may still call **`query_property_history`** for bounded non-numeric history when it helps the comparison; otherwise rely on current values and alert summary evidence and state that a numeric trend chart was not produced.

### Final answer rule

Answer in this order:

1. resolved asset type and the two resolved Things
2. current alert comparison
3. main problem property or properties
4. current value comparison for those properties
5. recent trend summary and chart reference, if numeric trend history was queried
6. recommendation: which asset appears less healthy and why
7. evidence gaps, if any

Use only evidence from tool results. Do not invent missing properties, thresholds, alert rows, causes, or trend points.

```parler-task-checklist-v1
{
  "schemaVersion": 1,
  "requiredEvidence": [
    {
      "id": "asset_type_resolution",
      "description": "Call resolve_asset_type (or list_asset_types when listing/disambiguating); obtain entityType, entityName, and criticalProperties[] for the chosen asset type, or ask the user for the asset type."
    },
    {
      "id": "candidate_asset_listing",
      "tool": "query_entities_by_taxonomy",
      "description": "When exact identifier lookup fails, list candidate Things under the taxonomy row and use normalized matching across Thing name and projected identity fields before asking the user."
    },
    {
      "id": "first_asset_resolution",
      "tool": "query_entities_by_taxonomy",
      "description": "Resolve the first user-supplied asset identifier to exactly one Thing under the taxonomy row."
    },
    {
      "id": "second_asset_resolution",
      "tool": "query_entities_by_taxonomy",
      "description": "Resolve the second user-supplied asset identifier to exactly one Thing under the taxonomy row."
    },
    {
      "id": "asset_alert_summaries",
      "tool": "query_alert_summary",
      "cardinality": "one",
      "description": "Read current alert summary rollups for both resolved assets in one query_alert_summary thingNames[] call."
    },
    {
      "id": "problem_property_selection",
      "description": "Choose the primary problem property from alert sourceProperty evidence or exact property metadata."
    },
    {
      "id": "asset_current_values",
      "tool": "get_property_values",
      "cardinality": "one_or_more",
      "description": "Read current identity, critical, and alert-related property values for both resolved assets."
    },
    {
      "id": "trend_window_resolution",
      "description": "Resolve the requested time window, defaulting to the past 24 hours when omitted."
    },
    {
      "id": "primary_numeric_trends",
      "tool": "query_property_history",
      "cardinality": "one_or_more",
      "description": "When the primary problem property is numeric, query the same property over the same time window for both assets."
    },
    {
      "id": "asset_pair_health_comparison",
      "description": "Compare the two assets by alert evidence, current values, recent trends when available, and evidence gaps."
    }
  ]
}
```
