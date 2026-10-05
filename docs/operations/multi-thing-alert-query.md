# Multi-Thing Alert Query

**Status:** Implemented. **`query_alert_summary`** accepts **`thingNames[]`** (1–25); N≥2 returns
**`ALERT_SUMMARY_MULTI`**; **`query_alert_history`** / **`acknowledge_alerts`** remain scalar
**`thingName`**. Normative contract: **`CONTRACTS/TAXONOMY_RESOLVER.md`** §7.2. The three-tool alert
design is [`alert-solution.md`](./alert-solution.md).

---

## 1. Why multi-Thing summary

The "compare alerts across many assets" intent — a first-class operator question on Mashup cards
(*"compare the health status of assets between USA and Germany"*) — would otherwise be served by
calling a single-Thing alert tool **once per Thing**. That fan-out:

1. **adds N round-trips** (each an LLM round + a tool dispatch), and
2. **grows conversation history by N tool-result pairs**, which can push a turn over the request cap
   (`OVERHEAD_EXCEEDS_CAP`, no answer) — the fixed tool block plus the accumulated history overshoot.

Letting one summary call cover **multiple Things** reduces the comparison to a **single** dispatch
and a **single** result pair, without adding a fourth "fleet" alert tool.

The design constraint is **result shape under the inline cap**: built-in InfoTable results are
sampled at **`ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT` (20)** rows, so naively concatenating rows
from N Things would show ~20 rows total with **no per-Thing fairness**. The N≥2 path therefore
returns one compact rollup per Thing (§4).

History is a sample-bearing timeline whose value is the individual events, so it stays single-Thing;
comparison is summary's job. `acknowledge_alerts` is mutating and stays single-Thing.

| Tool | Platform service | Required arg | Returns |
|------|------------------|--------------|---------|
| `query_alert_summary` | `AlertFunctions.QueryAlertSummaryForThing` (per-Thing loop inside one dispatch) | **`thingNames[]`** (1–25) | N=1: current alert **summary** rows for **one** Thing; N≥2: per-Thing rollups in **`ALERT_SUMMARY_MULTI`** |
| `query_alert_history` | `AlertFunctions.QueryAlertHistory` | `thingName` (scalar) | time-bounded alert **history** rows for **one** Thing |
| `acknowledge_alerts` | `AlertFunctions.AcknowledgeAlert*` | `thingName` (scalar) | acknowledges alerts on **one** Thing (mutating) |

## 2. Schema

For `query_alert_summary` (`BuiltInTools.queryAlertSummaryDef()`):

- **`thingNames`**: `{ "type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 25 }`,
  `required = {"thingNames"}`. There is no scalar `thingName` alias.
- All filter args (`ackState`, `propertyName`, `alertName`, `alertType`, `priorityMin/Max`, `sort`,
  `limit`, `advancedQuery`) keep their meaning and apply to **every** Thing in the call.
- **Missing / empty array:** absent `thingNames`, null, or `[]` → whole-call
  **`THINGNAME_VALUE_REQUIRED`** with **`parameterName: thingNames`**.
- **Upper bound:** **`maxThingNamesPerCall = 25`** (executor-enforced). Over limit → whole-call
  **`THING_NAMES_LIMIT_EXCEEDED`** (split the fleet or scope tighter).

The single-Thing case is **`thingNames` length 1**; the output shape branches on length (§4).

## 3. Executor: bounded fan-out with per-Thing identity gating

`AlertToolsExecutor.doQueryAlertSummary`:

- Parses `thingNames` and enforces the 25-name cap before any platform work.
- Gates **each** name through **`ScalarThingnamePreflight.gateApplicationThings`**. Canonical names
  proceed; non-canonical names are collected into **`identityErrors[]`** (each entry reuses the
  scalar **`IDENTITY_RESOLUTION_REQUIRED`** envelope fields + optional **`recoveryHint`**) without
  failing the whole call when at least one name resolves.
- **Zero resolved Things:** whole-call **`status: error`**, **`code: IDENTITY_RESOLUTION_REQUIRED`**,
  **`parameterName: thingNames`**, plus **`identityErrors[]`**.
- Calls `QueryAlertSummaryForThing` for each canonical name inside a **per-Thing try/catch**. A
  platform failure for one Thing records a **`byThing[]` error entry** (`status: error`, `code`,
  `message`) and the comparison continues.
- **All resolved, all sub-calls fail:** whole-call **`status: error`** with
  **`code: QUERY_ALERT_SUMMARY_ERROR`** (or the dominant platform code) and per-Thing detail.
- Per-call filter semantics (`ackState`, sort, filters) apply to each sub-query.

| `thingNames.length` | Path |
|---------------------|------|
| **1** (and identity passes) | `formatBuiltinInfotableResult` → `INFOTABLE` / `INFOTABLE_LARGE` (table wire, cohort merge input and presentation titles unchanged). |
| **≥ 2** | Rollup builder (`AlertSummaryMultiRollup`) → `resultKind: ALERT_SUMMARY_MULTI` (§4). |

History and acknowledge bind a **single** Thing via the scalar
**`ScalarThingnamePreflight.gateApplicationThing`**.

## 4. Result shape

### 4.1 N=1 — flat INFOTABLE

When exactly one canonical Thing resolves, the model calls with `thingNames: ["…"]` and receives the
single-Thing `INFOTABLE` / `INFOTABLE_LARGE` JSON (`rows` / `sampleRows`, `thingName` extra).

### 4.2 N≥2 — `ALERT_SUMMARY_MULTI` rollup envelope

A **per-Thing aggregate array**, not a flat concatenation of rows:

```json
{
  "status": "success",
  "resultKind": "ALERT_SUMMARY_MULTI",
  "completeness": "complete",
  "thingsRequested": 14,
  "thingsSucceeded": 13,
  "thingsFailedIdentity": 1,
  "thingsFailedService": 0,
  "byThing": [
    {
      "thingName": "SE.CellFab…StackingRobot-02",
      "status": "success",
      "totalAlerts": 7,
      "unackedCount": 3,
      "byPriority": { "high": 2, "medium": 4, "low": 1 },
      "topAlerts": [
        { "alertName": "…", "sourceProperty": "…", "priority": 900, "timestamp": "…" }
      ],
      "rowCount": 7,
      "cacheId": "…"
    },
    {
      "thingName": "germany",
      "status": "error",
      "code": "IDENTITY_RESOLUTION_REQUIRED",
      "message": "…",
      "recoveryHint": { "tool": "resolve_thing", "argument": "text" }
    }
  ],
  "identityErrors": [
    { "thingName": "germany", "code": "IDENTITY_RESOLUTION_REQUIRED", "recoveryHint": "…" }
  ],
  "extras": { "ackState": "all", "limitApplied": 100 }
}
```

| Field | Rule |
|-------|------|
| **`completeness`** | `"complete"` when `thingsFailedIdentity + thingsFailedService == 0`; `"partial"` otherwise. Routing and skills treat `"partial"` as an incomplete comparison until gaps are resolved. |
| **`byPriority`** | Priority-band breakdown. There is no `byProperty` map — use **`topAlerts[].sourceProperty`**. |
| **`topAlerts`** | At most **3** entries per Thing. **Order:** priority **desc**, then timestamp **desc**, then **`alertName`** asc. |
| **`cacheId`** | Present on a success entry only when that Thing's raw row count exceeds the per-Thing inline sample threshold (full drill-down through the conversation cache). |
| **`identityErrors[]`** | Parallel list of identity failures (they may also appear in `byThing[]`). |
| **`byThing[]` error entries** | Platform sub-call failures use `status: error` + normalized `code` / `message`. |

The executor computes counts and **`topAlerts`** from each `QueryAlertSummaryForThing` InfoTable and
caches the full table behind the per-Thing **`cacheId`** when needed.

`ALERT_SUMMARY_MULTI` does not produce an automatic table/chart wire frame.

## 5. Egress

`ToolResultEgressGateway.compactForLlmAppend` samples most JSON arrays at **`ARRAY_SAMPLE_LIMIT`
(20)** and, when serialized size exceeds **`LLM_EVIDENCE_CHARS_SOFT_CAP` (8192)**, may sample again
at **5** entries. When **`resultKind` is `ALERT_SUMMARY_MULTI`**, **`byThing`** and
**`identityErrors`** are **non-samplable** evidence arrays (same class as schema arrays), and
**`completeness`**, **`thingsRequested`**, **`thingsSucceeded`** and related counters are egress
priority fields. `topAlerts` is capped at 3 by the executor. A JUnit test builds a worst-case
14-Thing rollup and asserts every success `thingName` and `completeness` survive with no
`_egress.reducedFields.byThing` sampling marker.

## 6. Downstream consumers

| Consumer | N=1 (flat INFOTABLE) | N≥2 (`ALERT_SUMMARY_MULTI`) |
|----------|----------------------|----------------------------|
| **`PlaybookAlertSummaryRows`** | Parses `rows` / `sampleRows` | Parses `byThing[]` success entries |
| **`TabularChartRoundHooks`** | Unchanged | No auto table/chart wire |
| **`LlmToolResultCohortMerger`** | Safety net for residual fan-out | No merge on rollup bodies |
| **`ParlerTableWirePresentationTitles`** | Unchanged | No table wire for rollup |

## 7. Model-facing guidance

- The routing guide (`llm_tool_routing_guide.txt`, "Alert comparison") and `AlertPromptDefaults`
  prescribe **one** `query_alert_summary` call with the resolved Thing set, returning a per-Thing
  rollup, instead of a per-Thing loop.
- The sample skills (`region_health`, `asset_pair_health`) and playbooks
  (`cross_region_health`, `cross_asset_pair_health`) under `dev_data/` pass the Thing list once and
  consume `byThing[]`.

## 8. Out of scope

- Multi-Thing `acknowledge_alerts` (mutating/safety-sensitive).
- Multi-Thing `query_alert_history`.
- A fourth "fleet" alert tool.
