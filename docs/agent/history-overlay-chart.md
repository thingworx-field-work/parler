# History overlay chart

**Status:** implemented — extension **0.1.205**, widget **0.1.89**, contracts **0.1.139**.
**Primary decision:** Replace the model-facing PoP / multi-series split with one history overlay
chart tool. The old model-facing tools are retired directly; there is no compatibility migration and
no executor-only fallback for the old names.

---

## 0. Orientation

This document is written for a reader who may not know the project history.

Parler is a ThingWorx-first AI agent. A user prompt arrives from a ThingWorx mashup, the Java agent
chooses LLM tools, tools query live ThingWorx Things / DataTables / ValueStreams / configuration
repositories, and the response may contain text plus structured chart/table artifacts. The UI
renders server-authored `ChartBlock` JSON.

Read these first:

| Path | Why it matters |
|---|---|
| `README.md` | Build entrypoints and contributing. |
| `CONTRACTS/CHART_CONTRACT.md` | Normative chart wire shape: `series[]`, `xAxisMode`, `elapsedDomain`, `y_reference_lines`. |
| `docs/agent/all-tools.md` | Tool-surface and context-cost background. |
| `docs/agent/time-interpretation.md` | Existing time parsing rules to reuse; do not create a new time parser. |

Key code areas:

| Area | Path |
|---|---|
| Built-in tool registration / schema | `parler-agent/src/main/java/com/thingworx/things/agent/tools/BuiltInTools.java` |
| Overlay executor | `parler-agent/src/main/java/com/thingworx/things/agent/tools/BuildHistoryOverlayChartExecutor.java` |
| Overlay chart builder | `parler-agent/src/main/java/com/thingworx/things/agent/HistoryOverlayChartBuilder.java` |
| Per-series window resolver | `parler-agent/src/main/java/com/thingworx/things/agent/tools/PeriodOverPeriodPeriodResolver.java` |
| Unit-compatibility helper | `parler-agent/src/main/java/com/thingworx/things/agent/PeriodOverPeriodPopSupport.java` (`validateUnitCompatibility` only) |
| History fetch helper | `parler-agent/src/main/java/com/thingworx/things/agent/HistorySeriesComposerSupport.java` |
| Single-history / numeric-history helpers | `parler-agent/src/main/java/com/thingworx/things/agent/tools/PropertyToolsExecutor.java` |
| Chart wire / reference-line cleaning | `parler-agent/src/main/java/com/thingworx/things/agent/ParlerChartWireSupport.java` |
| UI chart renderer | `parler-ui/components/chart-draw.js` |
| UI wire adapter validation | `parler-ui/lib/wireAdapter.js` |
| Playbook tool allowlist | `parler-agent/src/main/java/com/thingworx/things/agent/playbook/PlaybookToolAllowlist.java` |

**Removed classes (do not cite as current code):** `BuildPeriodOverPeriodChartExecutor`,
`BuildMultiSeriesHistoryChartExecutor`, `PeriodOverPeriodChartBuilder`,
`MultiSeriesHistoryChartBuilder`, `MultiSeriesSharedWindowResolver`, and the retired
`PeriodOverPeriodPopSupport` redirect helpers.

Live evidence that motivated the tool:

- The runtime under test exposed 33 model-facing tools.
- Host context was accepted and carried the selected Thing and selected time window.
- The prompt asked for one chart with:
  - selected Thing, selected window;
  - selected Thing, same window last week;
  - another Thing, same window last week.
- Without explicit guidance, the model used three `query_property_history` calls and emitted three
  charts.
- With explicit "use period-over-period chart tool", the agent emitted one PoP chart and then one
  multi-series chart because the PoP tool rejected same-window cross-Thing periods.

The problem is not missing data. The problem is that one user concept is split across two adjacent
model-facing chart tools.

---

## 1. Problem statement

Users think in terms of one chart:

```text
Overlay these history traces so I can compare them.
```

The traces may differ by:

- Thing;
- time window;
- both Thing and time window.

Before `build_history_overlay_chart` shipped, Parler exposed two separate model-facing tools for
this (both retired; do not call them):

| Former tool (retired) | Former mental model | Failure |
|---|---|---|
| `build_period_over_period_chart` | Different windows, elapsed X. | Rejected same-window cross-Thing periods, which broke mixed overlays. |
| `build_multi_series_history_chart` | Same window, multiple Things, absolute X. | Could not naturally express shifted windows. |

That split taught the model the wrong boundary. It then had to infer which tool owned mixed cases.
Live tests before the overlay consolidation showed that this boundary was not robust. The shipped
surface is one tool: `build_history_overlay_chart`.

### 1.1 Pre-overlay split (historical)

Before `build_history_overlay_chart` shipped, the split was enforced in code (not only routing
prose). Those mechanisms and classes are **gone**; this table is historical only:

| Historical mechanism | Former location (deleted) | Former effect |
|---|---|---|
| Same-window cross-Thing PoP redirect | `PeriodOverPeriodPopSupport.detectSameWindowCrossDevice` | Returned **`POP_MULTI_SERIES_UNAVAILABLE`** → model had to call **`build_multi_series_history_chart`**. |
| PoP equal-duration guard | `PeriodOverPeriodChartBuilder.validateDurationMatch` | Failed **`POP_WINDOW_DURATION_MISMATCH`** when period durations differed. |
| PoP duplicate-window guard | `PeriodOverPeriodChartBuilder.validateNoDuplicateWindows` | Failed **`POP_DUPLICATE_PERIOD`** when two periods resolved to the same window. |
| Multi-series shared window | `MultiSeriesSharedWindowResolver` | All series shared one top-level window; per-series calendar offsets were not expressible. |

The shipped overlay path is one model-facing tool (`build_history_overlay_chart`) with live
anchors `BuildHistoryOverlayChartExecutor`, `HistoryOverlayChartBuilder`,
`PeriodOverPeriodPeriodResolver`, and `PeriodOverPeriodPopSupport.validateUnitCompatibility`.

---

## 2. Design decision

One model-facing tool:

```text
build_history_overlay_chart
```

These two model-facing tools are retired directly:

```text
build_period_over_period_chart
build_multi_series_history_chart
```

There is no migration layer:

- the old names are not kept as executor-only tools;
- there are no compatibility wrappers for model calls;
- old tool names are not silently rewritten;
- playbooks or config that reference old names fail normal validation.

---

## 3. Core model

The unified tool reads several history series and overlays them in one chart.

The tool's central abstraction is:

```json
{
  "label": "Robot 01 - selected window",
  "thingName": "SE.CellFab.Model.Workunit.BOS-StackingRobot-01",
  "propertyName": "currentDraw",
  "startTime": "2026-07-02T04:00:39.177Z",
  "endTime": "2026-07-02T06:00:39.028Z"
}
```

All series use the same `propertyName`. That keeps unit validation, axis labeling, and LLM routing
simple. Multi-property overlays are not supported.

The key chart choice is the X-axis mode:

| Tool `xAxisMode` | Contract output | Meaning | Replaces |
|---|---|---|---|
| `absolute_time` | absent or `"absolute"` | Keep real timestamps. Good for same-window cross-Thing comparison. | `build_multi_series_history_chart` |
| `elapsed_time` | `"elapsed"` | Convert each point to seconds from that series' window start. Good for shifted-window shape comparison. | `build_period_over_period_chart` |
| `normalized_time` | `"normalized"` | Map each series window to the same normalized X domain. Good for different-duration shape comparison. | New |

PoP is therefore not a separate tool. It is history overlay with `xAxisMode = "elapsed_time"`.
Multi-series is not a separate tool. It is history overlay with `xAxisMode = "absolute_time"`.

---

## 4. Tool schema

Tool name:

```text
build_history_overlay_chart
```

Example:

```json
{
  "propertyName": "currentDraw",
  "xAxisMode": "elapsed_time",
  "title": "Current Draw - selected window vs last week",
  "yLabel": "Current Draw",
  "yReferenceLines": [
    { "y": 8.0, "label": "Upper limit", "role": "usl" },
    { "y": 2.0, "label": "Lower limit", "role": "lsl" }
  ],
  "series": [
    {
      "label": "Robot 01 - selected window",
      "thingName": "SE.CellFab.Model.Workunit.BOS-StackingRobot-01",
      "startTime": "2026-07-02T04:00:39.177Z",
      "endTime": "2026-07-02T06:00:39.028Z"
    },
    {
      "label": "Robot 01 - last week",
      "thingName": "SE.CellFab.Model.Workunit.BOS-StackingRobot-01",
      "startTime": "2026-06-25T04:00:39.177Z",
      "endTime": "2026-06-25T06:00:39.028Z"
    },
    {
      "label": "Robot 02 - last week",
      "thingName": "SE.CellFab.Model.Workunit.BOS-StackingRobot-02",
      "startTime": "2026-06-25T04:00:39.177Z",
      "endTime": "2026-06-25T06:00:39.028Z"
    }
  ]
}
```

Fields:

| Field | Type | Required | Notes |
|---|---:|:--:|---|
| `propertyName` | string | yes | Same numeric property for every series. |
| `series` | array | yes | 2..6 series. |
| `series[].label` | string | yes | Legend label. |
| `series[].thingName` | string | yes | Canonical Thing name. Existing Thing-name preflight applies. |
| `xAxisMode` | enum | no | **`absolute_time`**, **`elapsed_time`**, or **`normalized_time`** (see §5). When omitted, the server applies the two-branch default only (equivalent windows → absolute; else elapsed). **`normalized_time` requires explicit tool input** — no executor shape-intent inference. |
| `chart_kind` | enum | no | **`line`** (default) or **`scatter`** — parity with retired multi-series tool. |
| `title` | string | no | Chart title. |
| `yLabel` | string | no | Y-axis label. |
| `yReferenceLines` | array | no | Horizontal limit / target / warning lines. |

Each `series[]` must resolve exactly one time window. Supported shapes reuse the existing time
machinery:

```json
{ "startTime": "2026-07-02T04:00:00Z", "endTime": "2026-07-02T06:00:00Z" }
```

```json
{ "relativeDuration": "2h" }
```

```json
{ "relativeDuration": "2h", "anchorOffset": "7d" }
```

A top-level `anchorTime` may be used so relative windows in one request resolve against one clock.

---

## 5. X-axis mode selection

The model-facing description is explicit:

| User intent | Use |
|---|---|
| "Compare these Things in the selected window" | `absolute_time` |
| "This window vs last week / yesterday / previous shift" | `elapsed_time` |
| "Compare shape/profile even though durations differ" | `normalized_time` |

If the user does not specify a mode, the executor applies deterministic defaults (**Q2**): the tool description teaches explicit choice; omitted field is resolved server-side.

**Default heuristic when `xAxisMode` is omitted:**

1. If all resolved series windows are equivalent within tolerance (`WINDOW_EQUALITY_TOLERANCE_SECONDS`),
   default to **`absolute_time`**.
2. Otherwise default to **`elapsed_time`**.

The executor **does not** infer shape/profile intent from omitted mode (P2-Q6). The model MUST
set **`xAxisMode: "normalized_time"`** explicitly for shape/profile / different-duration comparisons.
Routing text and tool schema teach when to use each mode.

Do not reject merely because two series have:

- the same time window but different Things;
- different time windows but the same Thing;
- different time windows and different Things;
- different window durations.

Reject only when the chart would be invalid or misleading for concrete reasons: missing window,
non-numeric property, incompatible known units, too many series, impossible point budget, or exact
duplicate series (same `thingName`, `propertyName`, resolved `start`, resolved `end`).

**Duplicate rule (Q4):** reject identical `(thingName, propertyName, start, end)` pairs
only. **Allow** two different Things sharing the same resolved calendar window (required for
acceptance §11.1 mixed overlay).

---

## 6. Window duration and alignment

`elapsed_time` does not require equal durations. The X domain can be:

```text
0 .. max(series duration seconds)
```

Shorter series simply stop earlier. The chart should not fabricate trailing points.

`normalized_time` maps every series to the same normalized domain:

```text
normalizedX = (timestamp - windowStart) / (windowEnd - windowStart)
```

The first version does not need interpolation or resampling. Uneven sample timestamps are acceptable
for drawing. If later statistical comparison requires point-by-point alignment, that should be a
separate explicit resampling option.

If the user asks to compare by fixed unit length, such as "align by the first 30 minutes" or "align
by cycle percentage", the tool should express that through `elapsed_time` or `normalized_time`
rather than rejecting the chart.

---

## 7. Reference lines and limits

Chart reference lines are first-class tool input.

The current chart contract and UI already support:

```json
"y_reference_lines": [
  { "y": 25, "role": "ucl", "label": "UCL" }
]
```

Supported roles:

```text
usl, ucl, lcl, lsl, target, limit, warning
```

The tool accepts:

```json
{
  "yReferenceLines": [
    { "y": 8.0, "label": "Upper limit", "role": "usl" },
    { "y": 2.0, "label": "Lower limit", "role": "lsl" },
    { "y": 5.0, "label": "Target", "role": "target" }
  ]
}
```

Executor behavior:

- convert `yReferenceLines` to `ChartBlock.y_reference_lines`;
- cap at 12 lines, matching `CHART_CONTRACT.md`;
- **`y` must be finite numeric** — non-finite or malformed entries **fail** with
  **`HISTORY_OVERLAY_INVALID_REFERENCE_LINE`** (C4: hard error, not silent drop);
- unknown **role** normalizes to **`limit`**;
- reference lines are chart-global;
- the UI must show the lines and expand the Y domain when needed.

Acceptance must include upper-limit and lower-limit prompts, not only trend prompts.

---

## 8. Tool surface

The history chart surface is:

```text
query_property_history
build_history_overlay_chart
```

Rules:

- `query_property_history` remains for one Thing / one property / one window; it is not the happy path for overlay
  charts.
- `build_period_over_period_chart` and `build_multi_series_history_chart` are not model-facing built-ins, are not
  kept as executor-only tools, and are not accepted by playbook validation.

---

## 9. Implementation outline

### 9.1 Internal series model

Use one internal representation:

```java
ResolvedHistoryOverlaySeries {
  String label;
  String thingName;
  String propertyName;
  Instant windowStart;
  Instant windowEnd;
  String unit;
  List<Point> points;
}
```

Implementation anchors:

- `BuildHistoryOverlayChartExecutor`
- `HistoryOverlayChartBuilder` (owns window-equality tolerance)
- `HistorySeriesComposerSupport`
- `PeriodOverPeriodPeriodResolver`
- `PeriodOverPeriodPopSupport.validateUnitCompatibility`
- `PropertyToolsExecutor` history helpers used by the overlay path

The registered model-facing `ToolDefinition` is only `build_history_overlay_chart`. The former
PoP/multi-series executors, builders, and `MultiSeriesSharedWindowResolver` no longer exist.

### 9.2 Builder output

For `absolute_time`:

- output normal datetime `x[]`;
- output `requested_time_range` when all windows share one range;
- omit `xAxisMode` or set contract value `"absolute"`.

For `elapsed_time`:

- output numeric elapsed seconds as `x[]`;
- output `xAxisMode = "elapsed"`;
- output `elapsedDomain`;
- omit `requested_time_range`.

For `normalized_time`:

- extend `CHART_CONTRACT.md` with a normalized X-axis mode;
- output normalized numeric `x[]`;
- use a stable domain such as `0..1` or `0..100`, chosen explicitly in contract text;
- do not require resampling in the first delivery.

For all modes:

- attach `y_reference_lines` when requested;
- include provenance in `source`;
- return a structured success payload with `code = CHART_EMITTED`;
- add exactly one pending chart block for one successful tool call.

#### 9.2.1 Series cache sidecar (`seriesCaches`)

On success, when at least one series produced ≥1 fetched numeric point, the tool JSON MAY include
additive **`seriesCaches`**: an array of per-series descriptors for the **full pre-subsample** source
series (written by `NumericHistoryCacheWriter`, the same writer as numeric-history cache), so
`fetch_cached_result` / `tabulate_cached_result` / `analyze_cached_result` can reuse them. Chart
fair-subsample emit remains independent.

| Field | Meaning |
|---|---|
| `label` | Series label |
| `thingName` | Resolved Thing name |
| `propertyName` | Shared property |
| `cacheId` | Opaque conversation cache id |
| `totalRows` | Cached row count (fetched points) |
| `columns[]` | Real written columns `{name, baseType}` in source order (CM-1) |
| `timeColumn` / `valueColumn` | Writer-declared column roles, validated against the written columns and stamped on the cache's runtime descriptor in the same store (CM-0); omitted when the declaration was invalid |
| `windowStart` / `windowEnd` | The **resolved request window** (ISO-8601 UTC) — not data coverage; `completeness` stays unpublished / `UNKNOWN` |
| `resolvedTimeZone` | IANA zone used to resolve the window; omitted when the bounds were explicit ISO |

The same store also stamps each series' resolved `thingName` / `propertyName` on the cache's runtime
descriptor as its subject identity (CM-4), which `analyze_cached_result` echoes in `sources[]`.
`columns`, `timeColumn` and `valueColumn` come from the same `StoredSeriesCache` value the writer
returned; the executor does not reopen the cache or maintain a second mapping. Any producer that
stores a table through `NumericHistoryCacheWriter.storeWithRoles` (or stamps the same optional
descriptor fields itself) gets identical downstream behavior; consumers never identify roles by tool
name or column name.

Zero-point series are omitted (they already appear in `missingSeries` / `missingPeriods`). Store
failures soft-skip that series entry; chart emission still succeeds. Series caches are published
**only after** chart build succeeds — a post-fetch chart validation fault must leave **no** new
live cache entries and no `seriesCaches` field. This is a **handle-only sidecar** — not an
EMPTY/INLINE/LARGE rewrite of the chart envelope (chart tools stay on
`CHART_EMITTED`; `seriesCaches[].cacheId` hits/misses through the ordinary
`fetch_cached_result` / `tabulate_cached_result` uniform `CACHE_MISS` path). The sidecar is
descriptive, not a second data lane: it carries handles, columns, roles and the request window, no
rows. Public `completeness`/`counts` are not published on this tool JSON. No `CONTRACTS/CHART_CONTRACT`
change (ChartBlock wire unchanged).

### 9.3 Error codes

Codes:

| Code | Meaning |
|---|---|
| `HISTORY_OVERLAY_TOO_FEW_SERIES` | Need at least 2 series. |
| `HISTORY_OVERLAY_TOO_MANY_SERIES` | Exceeds configured cap. |
| `HISTORY_OVERLAY_MISSING_PROPERTY` | No `propertyName`. |
| `HISTORY_OVERLAY_MISSING_THING` | A series lacks `thingName`. |
| `HISTORY_OVERLAY_INVALID_ANCHOR_TIME` | Root `anchorTime` / `anchorInstant` is not a valid ISO-8601 instant. |
| `HISTORY_OVERLAY_INVALID_SERIES_ENTRY` | A `series[]` element is missing or not a JSON object. |
| `HISTORY_OVERLAY_MISSING_SERIES_LABEL` | A `series[]` element lacks a non-empty `label`. |
| `HISTORY_OVERLAY_INVALID_TIME_WINDOW` | A series cannot resolve a time window, or normalized mode rejects a non-positive duration. |
| `HISTORY_OVERLAY_PROPERTY_NOT_NUMERIC` | Property is not chartable numeric on one Thing. |
| `HISTORY_OVERLAY_UNIT_MISMATCH` | Known non-empty units differ across Things. |
| `HISTORY_OVERLAY_INVALID_X_AXIS_MODE` | Unknown X-axis mode. |
| `HISTORY_OVERLAY_NO_DATA` | No series has chartable points. |
| `HISTORY_OVERLAY_INVALID_REFERENCE_LINE` | Non-finite or malformed reference-line input. |

Do not return `POP_MULTI_SERIES_UNAVAILABLE` from this tool. That code belongs to the retired split.

---

## 10. Contract and UI impact

Existing contract already covers:

- line charts;
- multiple `series[]`;
- `xAxisMode = "elapsed"`;
- `elapsedDomain`;
- `y_reference_lines`.

`CHART_CONTRACT.md` §3.0e is the single producer section for **`build_history_overlay_chart`**
(absolute, elapsed, and normalized modes as X-axis variants of one tool):

1. Elapsed charts **may** include different Things and unequal window durations; `elapsedDomain.end` = **max** series
   duration in seconds (Q3).
2. Producer notes cover the `build_history_overlay_chart` success payload and scatter `chart_kind`.
3. `normalized_time` maps to contract `"normalized"` mode with widget normalized-axis rendering (§14).

UI: elapsed, normalized, and reference-line rendering; one chart block with multiple series renders as one artifact.

---

## 11. Acceptance criteria

### 11.1 Mixed shifted + cross-Thing overlay

Prompt:

```text
please show me the trend of Current Draw in the selected time window and the same in last week,
also the same property from BOS StackingRobot 02 in the same time window in last week
```

Expected:

- one `build_history_overlay_chart` call;
- one emitted chart;
- `parlerChartWireEmittedCount = 1`;
- `series.length = 3`;
- X-axis mode is `elapsed_time` / contract elapsed;
- no calls to retired chart tools;
- no repeated `query_property_history` chart assembly path.

### 11.2 Same-window cross-Thing

Prompt:

```text
compare Current Draw for BOS StackingRobot 01 and BOS StackingRobot 02 in the selected time window
```

Expected:

- one emitted chart;
- `series.length = 2`;
- X-axis mode defaults to `absolute_time`;
- chart uses real timestamps.

### 11.3 Same Thing shifted windows

Prompt:

```text
show Current Draw for BOS StackingRobot 01 in the selected window and the same window last week
```

Expected:

- one emitted chart;
- `series.length = 2`;
- X-axis mode defaults to `elapsed_time`.

### 11.4 Limits

Prompt:

```text
show Current Draw for BOS StackingRobot 01 and BOS StackingRobot 02 in the selected window,
with upper limit 8 amps and lower limit 2 amps
```

Expected:

- one emitted chart;
- two data series;
- `y_reference_lines` includes an upper line at 8 and a lower line at 2;
- reference lines render and remain visible even if data is outside the band.

### 11.5 Different-duration shape comparison

Prompt:

```text
compare the startup shape of BOS StackingRobot 01 over the first 30 minutes today
with BOS StackingRobot 02 over the first 45 minutes yesterday
```

Expected:

- no rejection solely because windows have different durations;
- **preferred (1.0):** explicit **`normalized_time`** → normalized X on wire;
- **acceptable fallback (0.75):** **`elapsed_time`** + prose that one series has a longer span;
- eval suites MUST NOT reject the elapsed fallback when normalized is preferred.

### 11.6 Removal of old tools

Expected:

- runtime snapshot does not list `build_period_over_period_chart` or `build_multi_series_history_chart`;
- playbook allowlist does not list old names; a playbook that uses an old name fails validation;
- **`BuiltInTools.java`** tool descriptions and **`llm_tool_routing_guide.txt`** do not reference old tool names or
  **`POP_MULTI_SERIES_UNAVAILABLE`** / **`ROUTE_MULTI_SERIES`**.

---

## 12. Design decisions

| Id | Decision |
|---|---|
| **Q1** | `normalized_time` is an explicit mode (§14). For different-duration shape comparison, `elapsed_time` plus prose that one series has a longer span remains an acceptable fallback. |
| **Q2** | The tool description teaches explicit `xAxisMode`; the executor applies the §5 two-branch default when omitted. |
| **Q3** | `elapsedDomain.end` = **max(durationSeconds)** across emitted series. |
| **Q4 / C3** | Reject exact duplicate `(thingName, propertyName, start, end)` only; allow the same window on different Things. No `POP_DUPLICATE_PERIOD` / redirect guards on the unified path. |
| **Q5** | `series[].sourceWindow` is **required** on elapsed output only; absolute mode uses `requested_time_range` when windows share one range (multi-series wire style). |
| **Q6** | **`chart_kind` line (default) + scatter** — parity with the retired multi-series tool; not a new UI mode. |
| **Q7** | Single constant **`HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS = 5000`**; fair per-series subsample. |
| **Q8** | `docs/agent/evals/history_overlay_v1.yaml` covers same-window, shifted, mixed, limits, duplicate rejection, and old-tool removal; legacy eval filenames are thin wrappers that call only `build_history_overlay_chart`. |
| **IQ4** | The overlay path is self-contained (`HistoryOverlayChartBuilder` + `BuildHistoryOverlayChartExecutor`); the former PoP/multi-series executors and builders are deleted. |
| **IQ5 / C5** | `query_property_history` stays for single-series; the routing guide **discourages** repeated history calls for overlay assembly (steering only). |
| **C2** | Contract §3.0e is one `build_history_overlay_chart` producer section. |
| **C4** | Reference lines: hard error on non-finite/malformed `y`; unknown role → `limit`. |

---

## 13. Schema delta vs retired tools

| Aspect | `build_period_over_period_chart` | `build_multi_series_history_chart` | `build_history_overlay_chart` |
|---|---|---|---|
| Series input | `periods[]` with per-period window fields | `series[]` (Thing only) + **one shared** top-level window | `series[]` with **per-series** window resolution |
| X axis | Always elapsed | Always absolute | **`xAxisMode`** selects mode |
| Cross-Thing same window | Redirect / error | Supported | Supported (`absolute_time`) |
| Cross-Thing shifted windows | Rejected / redirect | Not expressible | Supported (`elapsed_time`) |
| Reference lines | Not in tool schema today | Not in tool schema today | **`yReferenceLines`** first-class input (§7) |
| `chart_kind` | Line only | Line + scatter | Line + scatter |
| Mixed duration elapsed | Rejected (`POP_WINDOW_DURATION_MISMATCH`) | N/A | Allowed; domain = max duration (§6) |

---

## 14. Normalized axis (`normalized_time`)

| Id | Decision |
|---|---|
| **P2-Q1** | **`normalized_time`** is in the tool enum and the routing guide. |
| **P2-Q2** | Wire **`xAxisMode: "normalized"`**. |
| **P2-Q3** | Required **`normalizedDomain: { start: 0, end: 1 }`**. |
| **P2-Q4** | `normalizedX = (t - start) / duration`; **clamp to [0, 1]** in builder; zero-duration → **`HISTORY_OVERLAY_INVALID_TIME_WINDOW`**. |
| **P2-Q5** | Omit **`requested_time_range`** / **`elapsedDomain`**; keep per-series **`sourceWindow`**. |
| **P2-Q6** | Omitted mode keeps the two-branch default only — **no** executor shape-intent inference. |
| **P2-Q7** | UI percent ticks **`0%`…`100%`** (**`wireAdapter.js`**, **`chart-draw.js`**, **`types.js`** JSDoc). |
| **P2-Q8** | Wire semantics are in **`CHART_CONTRACT.md`** §3.0e. |
| **P2-Q9** | JUnit §11.5, wire validation tests, eval with normalized preferred + elapsed fallback. |

### Not supported

- Point-by-point resampling / interpolation across series.
- Multi-property overlay.
- New chart kinds beyond line/scatter.
