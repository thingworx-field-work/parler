# Cross-region health playbook — current-value evidence (design)

**Status:** Implemented in-tree (`parler-agent` derive op + `dev_data/playbooks/cross_region_health/playbook.json`).
**Scope:** Close the behavioral gap where **skill** answers often cited **live** `robotSpeed` / `operationalVoltage` (and similar) from **`get_property_values`**, while **`cross_region_health`** **`llm_summary`** tended to say there was **no evidence** for those dimensions because **only alert-derived rows** were prominent in the evidence pack.

This document is the **canonical** design note for this behavior. It does **not** change wire contracts.

---

## Problem statement

1. **`build_property_union`** unions **taxonomy critical properties** with **alert `sourceProperty` names** to bound **`get_property_values`** calls.
2. **`summarize_region_health`** already consumes the **`values_by_asset`** fan-out for **`topProperties`**, but that path is **heavily capped** (round-robin, small cap) and is optimized for a **short** region table, not a full cross-region narrative.
3. Without a dedicated node, **`llm_summary`** **`evidenceRefs`** did **not** include a dedicated node whose primary job is **“current numeric / scalar reads by region”**, so the closing model often treated speed/voltage as **unobserved** when there were **no alert rows** on those properties.

---

## Approach

### 1. New derive op: `summarize_current_values_by_region`

**Location:** `PlaybookDeriveOps` (`parler-agent`), allow-listed in `PlaybookValidator` **`V1A_DERIVE_OPS`**.

**Inputs (playbook JSON `args`):**

| Field | Role |
|-------|------|
| `assetsRef` | Resolves to **`region_entities.output.assets`** (region + Thing name list). |
| `valuesFanOutNodeId` | Fan-out id for **`get_property_values`** (default `values_by_asset`). |
| `propertyNames` | Resolved list (typically **`$var` `propertyUnion.names`**) — same names passed into **`get_property_values`**. |
| `maxPropertiesPerRegion` | Cap distinct properties **summarized** per region (default **24**, clamped to property-union cap). The shipped **`cross_region_health`** playbook uses **12** so typical two-region SCPA runs stay under the node byte cap. |
| `maxExamplesPerProperty` | Cap example Things per property per region (default **3**, upper clamp **12**). Shipped playbook uses **1** to keep structured output small; aggregates still scan all Things. |
| `excludePropertyNames` | Optional string array: property names **dropped before** per-region stats (identity/display fields such as **`PTCDisplayName`** that inflate size without operational signal). |

**Output (`output`):**

- `regions[]` — each `{ "region", "properties": [ { "name", "successfulReads", "failedReads", "examples": [{thingName,value}], optional numericMin/Max/Mean } ] }`. Entries with **no** successful read, **no** failed read, and **no** examples are **omitted** (no per-property “all zero” noise).
- **`successfulReads`:** count of Things where the tool output was **`status: success`**, the property cell exists for that property name, and **`ok` is true** (includes **`ok: true`** with **`value: null`** — the read succeeded; numeric stats only use non-null numeric values).
- **`failedReads`:** count of Things where a matching property cell exists but **`ok` is false** (permission / type / missing value errors from the tool).
- Things with no matching cell, non-success tool status, or missing fan-out child are **not** counted in either counter.
- **`maxExamplesPerProperty`** caps **`examples[]` length only**; all Things in the region still contribute to **`successfulReads`**, **`failedReads`**, and numeric **min / max / mean**.
- **`PlaybookNodeEvidence`** attaches compact **human lines** via **`evidenceLines` only** (no duplicate **`evidenceText`** on this node) so the serialized derive result stays within the byte guard while **`PlaybookEvidenceFormatter`** still formats evidence from **`evidenceLines`**.

**Guards:**

- The derive node’s serialized JSON ( **`status` + `output` + `evidenceLines`** ) must stay **≤ 8192 UTF-8 bytes**, matching **`summarize_region_health`**. Overflow → **`PlaybookRunException`** **`evidence_too_large`**. Prefer **`excludePropertyNames`**, **`maxPropertiesPerRegion`**, **`maxExamplesPerProperty`**, and omitting empty property rows before raising the cap.

### 2. Playbook DAG changes (`cross_region_health/playbook.json`)

1. Insert node **`current_value_stats`** after **`values_by_asset`**, **`dependsOn`** `region_entities`, `property_union`, `values_by_asset`.
2. Extend **`region_summary`** **`dependsOn`** to include **`current_value_stats`** (ordering / cache semantics only).
3. Pass optional arg **`currentValueStatsRef`** into **`summarize_region_health`**; the Java implementation **ignores** this ref (the **`llm_summary`** path receives **`current_value_stats`** via **`evidenceRefs`**).
4. **`final_summary` (`llm_summary`):**
   - Add **`current_value_stats`** to **`evidenceRefs`**.
   - Extend **`prompt`** so the model **distinguishes**:
     - **Alert-backed** statements vs
     - **Current-value reads** (stats / examples present, no alert rows for that property), vs
     - True **evidence gaps** (neither alerts nor current-value stats for that dimension in the pack).

---

## Limitations and related behavior

- **Alert-to-Thing attribution.** Multi-Thing rollup extraction stamps the
  parent `thingName` onto each extracted alert row and `group_alerts_by_source_property` emits a bounded
  `alertAttribution` projection (region, `thingName`, `alertName`, `sourceProperty`; cap 40 rows,
  `alertAttributionOmitted` records truncation) rendered as per-Thing evidence lines. Group counts, caps, and gap
  notes are unchanged.
- **Property union is alert-driven for non-critical names.** If **`robotSpeed`** never enters **`propertyUnion.names`**, **`get_property_values`** will not read it and **`current_value_stats`** cannot invent it.
- **`summarize_region_health`** does not merge **`currentValueStatsRef`** into its **`output`** object; the resulting redundancy in **`llm_summary`** context is accepted.

---

## Testing

- **Unit:** `PlaybookDeriveOpsEvidenceTest` — rollup, **`List`**-backed `propertyUnion.names`, aggregate vs example caps, **`ok=false`**, **`ok=true` + null`**, **`build_property_union` → summarize** chain, **`coercePropertyNames`**, **omit empty property rows**, **`excludePropertyNames`**, **≤8KiB serialized node** stress case; **`PlaybookValidatorTest.v1aDeriveOps_allowlistsSummarizeCurrentValuesByRegion`**.
- **Catalog:** `PlaybookValidatorTest.crossRegionHealthDocument_passesV1aGuards` — validates **`dev_data/playbooks/cross_region_health/playbook.json`**.

---

## Related paths

- Playbook JSON: `dev_data/playbooks/cross_region_health/playbook.json`
- Derive implementation: `parler-agent/src/main/java/com/thingworx/things/agent/playbook/PlaybookDeriveOps.java`
- Validator allow-list: `parler-agent/src/main/java/com/thingworx/things/agent/playbook/PlaybookValidator.java`
