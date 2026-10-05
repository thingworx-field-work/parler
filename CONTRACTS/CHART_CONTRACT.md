# Chart contract — wire frame + `ChartBlock`

**Normative:** Single source for (1) the **server → client** **`type: "chart"`** wire object (including ThingWorx AlwaysOn), and (2) the nested **`chart`** payload **`ChartBlock`**.

**Related:** [`API_CONTRACT.md`](./API_CONTRACT.md), [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) §2–4, [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md) (structured **`type: "table"`** wire), [`agent-alwayson.md`](../docs/architecture/agent-alwayson.md) §6 (`ReceiveMessage`), reference client [`parler-ui/lib/wireAdapter.js`](../parler-ui/lib/wireAdapter.js), renderer [`parler-ui/components/chart-draw.js`](../parler-ui/components/chart-draw.js).

**Emitter:** Production **`ChartBlock`** payloads are built on the **ThingWorx** side (e.g. **`parler-agent`** / agent tools), not in this repo.

**Reference lines and SPC roles (non-normative):** [`chart-extensions-roadmap.md`](../docs/ui/chart-extensions-roadmap.md).

---

## 1. Purpose

Charts are **server-authored** structured graphics: coordinates are **not** taken from free-form model prose alone. The agent stack may call tools that return or build **`ChartBlock`** from ThingWorx data; the **client** (`<parler-ui>`) only renders validated wire JSON.

---

## 2. Wire envelope (`type: "chart"`)

Each chart is **one** JSON object on the server→client stream: one **`ReceiveMessage`** payload (default profile), or **one element** of a JSON array batch.

### 2.1 Required fields (all transports)

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | **Literal** `"chart"`. |
| `request_id` | string | Same turn id as `session.ack`, `activity`, `content.delta`, `done` for this assistant reply. |
| `chart` | object | **`ChartBlock`** — schema in **§3**. |

### 2.2 AlwaysOn / `ReceiveMessage` extension

Per **[`agent-alwayson.md`](../docs/architecture/agent-alwayson.md)** §6.2, every object sent through **`ReceiveMessage`** **MUST** also include:

| Field | Type | Description |
|-------|------|-------------|
| `conversation_id` | string | Non-empty; Conversation / thread id (snake_case). |

### 2.3 Example (AlwaysOn)

```json
{
  "type": "chart",
  "conversation_id": "agent-thread01",
  "request_id": "8fecb285-877c-4304-9d0c-0ce837830fc3",
  "chart": {
    "kind": "line",
    "chartId": "c1",
    "title": "Pressure (SteamSensor)",
    "x_label": "Time",
    "y_label": "Pressure",
    "series": [
      {
        "name": "Pressure",
        "x": ["2026-03-30T03:20:48.351Z", "2026-03-30T03:20:53.448Z"],
        "y": [18.61, 19.4]
      }
    ],
    "requested_time_range": {
      "start": "2026-03-30T03:15:00.000Z",
      "end": "2026-03-30T03:45:00.000Z"
    },
    "y_reference_lines": [{ "y": 25, "role": "ucl", "label": "UCL" }]
  }
}
```

Dedicated Parler WebSocket (non-AlwaysOn) may omit **`conversation_id`** on **`chart`** when the connection implies a single thread; see [`API_CONTRACT.md`](./API_CONTRACT.md).

### 2.4 Multiple `type: "chart"` frames per assistant turn

A single assistant reply sequence (**same `request_id`**) **MAY** include **more than one** distinct **`type: "chart"`** wire object (each its own payload in the stream). Emitters **SHOULD** assign distinct **`chart.chartId`** values (`c1`, `c2`, …) when emitting multiple charts so prose and exports can refer to them without positional wording.

### 2.5 Optional `cacheId` on tabular tool success envelopes

Qualifying **tabular** tool **success** JSON envelopes (including agent extended-tool **`INFOTABLE`** / **`INFOTABLE_LARGE`**-shaped service results, and built-in **`tabulate_cached_result`** aggregate/transform results such as **`CACHED_GROUP_METRIC_INLINE`**) **MAY** include an optional top-level string field **`cacheId`**: the opaque conversation-cache identifier under which the agent stored **that tool’s chartable table** for paging / charting (`fetch_cached_result`, `build_chart_from_tabular_result` with `source: "cache_id"`). For **`tabulate_cached_result`** transforms that produce a new derived **`InfoTable`**, **`cacheId`** refers to the **transformed** output table; the envelope **MAY** also carry **`sourceCacheId`** for the input table id. Raw **`analyze_entity_set`** success JSON also carries a **`cacheId`**, but **`build_chart_from_tabular_result`** **SHOULD** be driven from a **`tabulate_cached_result`** (or **`fetch_cached_result`**) envelope on that id so chart construction reuses the same tabular semantics as other entity-list charts (**`CONTRACTS/ENTITY_SET_TOOL.md`**). **`cacheId` is not derivable from tool call ids**; it is allocated when the table is stored. Clients and history hydrators **MUST** tolerate unknown additive top-level fields on tool-result JSON.

### 2.6 `type: "chart_group"` — chart-group manifest frame (C3b-1)

One assistant turn **MAY** declare **one** chart group (`declare_chart_group`). Every revision of its manifest is one wire object `{ "type": "chart_group", "conversation_id", "request_id", "group": ChartGroupManifest }` on the same channel and with the same ids as `chart` frames. The whole manifest is resent with `revision + 1` on every member change: revision 1 (all members `pending`) at declaration, so the manifest precedes its charts; one revision right after each member's `chart` frame (`ready` + `chartId`) or after a bound build fails (`no-data` / `error`); and one `final: true` revision at the end of the turn after every still-`pending` member converged (`cancelled` on a user stop, else `error` with `MEMBER_NOT_PRODUCED` or `DOWNLINK_FAILED`). Frames are never retransmitted. **C3b-2a:** when the group declared a shared category dimension, a member's newly appended colour keys are sent in a manifest revision **before** that member's `chart` frame (the dirty group is always flushed first), so a chart never draws with one colour and then changes; the budget becomes `2 + 2 × members` frames. Clients keep, per `(conversation, request, groupId)`, only a higher `revision` and ignore anything after `final`; clients that do not know the frame ignore it (§4.2 unknown types) and draw the member charts as single cards. `ChartBlock` carries no group field: the relation lives only in the manifest's `chartId` references.

```json
{ "type": "chart_group", "conversation_id": "agent-thread01", "request_id": "8fecb285-…",
  "group": { "groupId": "g1", "revision": 3, "title": "Oven temperature vs pressure", "layout": "auto", "final": true,
             "members": [ { "key": "temp", "order": 0, "name": "Temperature", "expectedType": "chart", "state": "ready", "chartId": "c1" },
                          { "key": "pres", "order": 1, "name": "Pressure", "expectedType": "chart", "state": "no-data", "code": "EMPTY_AFTER_FILTER" } ],
             "summary": { "expected": 2, "ready": 1, "noData": 1, "error": 0, "cancelled": 0, "final": true } } }
```

History: the final manifests of a turn are exported as `groups[]` on the `ai-parler-history-v1` assistant row (from the final assistant Stream row's `chartGroupsJson`; when that field is missing the group is rebuilt from the declaration with every member `error` / `TURN_INCOMPLETE`), and hydrated through the same validation. The exported `charts[]` of that row **MUST** keep the persisted `chartId` of every tabular chart (`build_chart_from_tabular_result`), and no id is invented for a chart persisted without one, so that each `ready` member resolves to a chart in the same row; a `ready` member whose `chartId` is absent from `charts[]` is shown as still loading. Numeric-history charts are not group members and MAY omit `chartId` on replay.

---

## 3. `ChartBlock` — JSON schema (logical)

The **`chart`** property **MUST** satisfy the following. Any server (ThingWorx agent tools, etc.) that emits charts on the Parler wire **MUST** use this shape wherever a nested chart object appears.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `kind` | string | yes | `line` \| `bar` \| `scatter` \| `pie` \| `histogram` \| `boxplot` \| `heatmap` |
| `chartId` | string | no | Optional stable id within one assistant run (e.g. `c1`, `c2`). Emitters **SHOULD** assign this when emitting new charts so final prose and export can refer to a chart without positional wording. Clients **MUST** accept charts that omit `chartId` (history and older servers). |
| `title` | string | no | Short chart title |
| `x_label` | string | no | X axis caption (e.g. `Time`). Wire `x[]` / `requested_time_range` use **ISO instants** (UTC `…Z` recommended on the wire); the reference client **formats axis ticks in browser-local wall time** per [`times-solution.md`](../docs/architecture/times-solution.md). |
| `y_label` | string | no | Y axis label |
| `series` | array | yes for `line` / `bar` / `scatter` / `pie` | One or more series (many servers emit one). **MUST be absent** on `histogram`, `boxplot` and `heatmap`, which carry their payload in `histogram` (§3.0f) / `boxplot` (§3.0g) / `heatmap` (§3.0h). |
| `histogram` | object | yes for `histogram` | The binned distribution (§3.0f): `edges`, `counts`, `densities`, `mode`, `validCount`, `excludedCount`, `belowRangeCount`, `aboveRangeCount`, `method`. **MUST be absent** on every other kind. |
| `boxplot` | object | yes for `boxplot` | The five-number summaries (§3.0g): `method` and `groups[]` of `key`, `n`, `excludedCount`, `min`, `whiskerLow`, `q1`, `median`, `q3`, `whiskerHigh`, `max`, `outliers`, `outlierCount`. **MUST be absent** on every other kind. |
| `heatmap` | object | yes for `heatmap` | The row × column matrix (§3.0h): `rows`, `cols`, `values`, `valueLabel`, `missingCount`. **MUST be absent** on every other kind. |
| `y_reference_lines` | array | no | Horizontal lines on the **Y** axis (limits, SPC). Cap at **12** entries. Allowed on `line`, `bar`, `scatter` and `boxplot`; not allowed on `histogram` or `heatmap`. |
| `requested_time_range` | object | no | **Time `line` / `scatter` only:** ISO-8601 UTC bounds for the **X** axis domain (the query / analysis window). When present and valid, the reference client **prefers** this domain over **data extent** so sparse windows show as **blank** gaps (no fabricated points). Ignored for **`bar`** and for numeric (non-datetime) X. **Omit** when `xAxisMode` is `"elapsed"` or `"normalized"`. |
| `xAxisMode` | string | no | **`line` / `scatter` only:** `"absolute"` (default when absent), `"elapsed"` (history overlay elapsed mode), or `"normalized"` (history overlay normalized 0..1 shape comparison). When `"elapsed"`, numeric X is **elapsed seconds** from each series' window start; **`requested_time_range`** MUST be omitted. When `"normalized"`, numeric X is **fraction of window** in `[0, 1]`; **`requested_time_range`** and **`elapsedDomain`** MUST be omitted. |
| `elapsedDomain` | object | yes when `xAxisMode === "elapsed"` | `{ "start": number, "end": number }` — fixed numeric X domain in **seconds** for the full comparison window (typically `{ "start": 0, "end": <durationSec> }`). |
| `normalizedDomain` | object | yes when `xAxisMode === "normalized"` | `{ "start": number, "end": number }` — fixed numeric X domain for normalized shape comparison; **MUST** be `{ "start": 0, "end": 1 }`. |
| `orientation` | string | no | **`bar` only:** `"vertical"` (default when absent) or `"horizontal"`. Presentation only: `series[].x` stays the category list, `series[].y` the values, `x_label` the category caption, `y_label` the value caption, and `y_reference_lines` value-axis lines (drawn vertical when horizontal). Emitters **MUST** omit the field for vertical bars and **MUST NOT** emit it on other kinds. Clients that predate this field ignore it (§4.2 unknown fields) and draw the same data as vertical bars. |
| `stackMode` | string | no | **`bar` only, two or more series:** `"stacked"` or `"percent"`; absent means grouped bars (`grouped` is the tool's default and is never written). Presentation only: `series[]` keeps the same values; the reference client accumulates positive and negative values separately from zero under `stacked`, and under `percent` draws each series' share `value / Σ(all emitted series in the category)` on a fixed `[0, 100]` axis, drawing nothing for a category whose total is `0`. Emitters **MUST NOT** emit `percent` with a negative value (`STACK_PERCENT_NEGATIVE`) and **MUST NOT** emit the field on other kinds or on a single-series bar. May be combined with `orientation`. Clients that predate this field draw grouped bars from the same data. |
| `source` | object | no | **Provenance** for server-built charts. Emitters **SHOULD** populate this for new charts (see **§3.0a**). Clients **MUST** tolerate absence (history / third-party). Unknown sub-fields **MAY** be ignored. |

### 3.0 Chart limits (named constants, v1 tabular chart builder)

The **reference** Parler tabular chart builder (`build_chart_from_tabular_result`) and UI validation **MUST** enforce the following limits unless a future contract revision changes them:

| Constant | Value | Meaning |
|----------|------:|---------|
| `PIE_DEFAULT_MAX_SLICES` | 8 | Default maximum **non-zero** pie slices before `top_with_other` merges tail into **`Other`**. |
| `PIE_HARD_MAX_SLICES` | 12 | Hard cap on **non-zero** slices when `pieSliceMode` is `all_nonzero` (user opt-in “show all”). |
| `BAR_MAX_SERIES` | 6 | Maximum **series** count for grouped **`bar`**. |
| `BAR_MAX_CATEGORIES` | 24 | Maximum **category** count (shared `series[0].x` length) for **`bar`**. |
| `HIST_MAX_BINS` | 50 | Maximum bin count of a **`histogram`** payload (`counts.length`), equal to `bin_numeric`'s `MAX_BINS`. |
| `BOX_MAX_GROUPS` | 24 | Maximum group count of a **`boxplot`** payload (`groups.length`), equal to `box_summary`'s group cap. |
| `BOX_MAX_SHOWN_OUTLIERS` | 20 | Maximum listed outliers per **`boxplot`** group (`outliers.length`); `outlierCount` still counts all of them. |
| `HEATMAP_MAX_ROWS` | 24 | Maximum row count of a **`heatmap`** payload (`rows.length`, the `seriesColumn` values). |
| `HEATMAP_MAX_COLS` | 48 | Maximum column count of a **`heatmap`** payload (`cols.length`, the `xColumn` values). |

### 3.0a `ChartBlock.source` (optional object)

When present, `source` **SHOULD** be a JSON object. For charts produced by **`build_chart_from_tabular_result`** in v1, the server **MUST** include `source` with at least the producer-required fields below.

| Field | Type | Required for v1 `build_chart_from_tabular_result` producer | Description |
|-------|------|:--:|-------------|
| `sourceResolved` | string | **yes** | How the tabular input was resolved (e.g. `cache_id`, `last_invoke`). |
| `sourceCacheId` | string | no | Tabular cache id when available. |
| `sourceToolCallId` | string | no | Originating tool call id when runtime exposes it. |
| `sourceResultKind` | string | no | e.g. `CACHED_TABULATE_GROUP_METRIC`. |
| `sourceColumns` | string[] | **yes** | Columns used for axes / series mapping. |
| `transformSummary` | string | no | Short deterministic transform description. |
| `rowCount` | number | **yes** | Input tabular row count used to build the chart. |
| `pointCount` | number | **yes** | Slice count or point count emitted in `series`. |
| `truncationApplied` | boolean | **yes** | `true` if top-N / **`Other`** / slice merge was applied. |
| `filledMissingCombinations` | number | no | Grouped **`bar`**: count of synthetic zero-filled `(category, series)` pairs. |
| `zeroValueCategoryCount` | number | no | **`pie`**: count of categories with numeric **zero** excluded before slice-cap / **`Other`** (audit). |
| `missingPeriods` | array | no | **History overlay elapsed mode (`build_history_overlay_chart`):** series with zero samples that were requested but not drawn; each item SHOULD include `label`, `thingName`, `propertyName`, `start`, `end`. |

### 3.0f `kind: "histogram"` — payload and REJECT rules

**Payload:** no `series[]`. `histogram` is an object with `edges` (`number[]`, strictly increasing, finite, length = `counts.length + 1`), `counts` (`number[]` of non-negative integral values, 1–`HIST_MAX_BINS` entries), `densities` (`number[]`, same length as `counts`), `mode` (`"count"` \| `"density"`: which array the reference client draws), `validCount`, `excludedCount`, `belowRangeCount`, `aboveRangeCount` (non-negative integral values) and `method` (`"explicit_edges_v1"` \| `"equal_width_v1"`). Bin `i` is `[edges[i], edges[i+1])` and the last bin also contains its right endpoint. The producer is the `bin_numeric` operator of `tabulate_cached_result` (`docs/agent/cached_tabular_tools.md`); the chart builder only validates and carries its numbers, never re-bins. `source.pointCount` is the bin count and `source.transformSummary` is `histogram(bin_numeric)`.

**REJECT** (server builder MUST NOT emit; client adapter MUST drop):

| Code | Condition |
|------|-----------|
| `SOURCE_SHAPE_MISMATCH` (builder) | The source table lacks the `bin_numeric` fixed columns; bins are not contiguous and strictly increasing; a count or a scalar count is negative or non-integral; scalars differ between rows; `Σcount ≠ validCount − belowRangeCount − aboveRangeCount` or `Σcount = 0`; any `density` differs from `count / (Σcount × width)` by a relative error above `1e-9` (an empty bin must have density exactly `0`); `method` is not registered; more than `HIST_MAX_BINS` rows. The tool answer carries a `recoveryHint` naming `bin_numeric`. |
| `INVALID_PARAMETERS` (builder tool) | `xColumn`, `yColumn`, `series`, `seriesColumn`, `yReferenceLines`, `orientation`, `pieSliceMode`, `pieMaxSlices` or `requestedTimeRange` supplied with `kind: histogram`; `histogramMode` outside `count` / `density`, or supplied with another kind. |
| `CHART_HISTOGRAM_INVALID` (client) | The adapter mirrors the builder's local invariants on the payload (§4.2): the same relations between the numbers given, no recomputation of bins. |

### 3.0g `kind: "boxplot"` — payload and REJECT rules

**Payload:** no `series[]`. `boxplot` is an object with `method` (`"tukey_1_5_iqr_linear_p_v1"`) and `groups` (1–`BOX_MAX_GROUPS` objects in source order). Each group carries `key` (unique, non-empty string), `n` (positive integral), `excludedCount` (non-negative integral), the seven finite statistics `min`, `whiskerLow`, `q1`, `median`, `q3`, `whiskerHigh`, `max` in non-decreasing order, `outliers` (`number[]`, each `< whiskerLow` or `> whiskerHigh` and within `[min, max]`, length exactly `min(outlierCount, BOX_MAX_SHOWN_OUTLIERS)`, farthest from its fence first) and `outlierCount` (integral, `0 ≤ outlierCount ≤ n`; when `0` the whiskers equal `min` / `max`). `y_reference_lines[]` (§3.1) MAY be present and lie on the value axis. The producer is the `box_summary` operator of `tabulate_cached_result` (`docs/agent/cached_tabular_tools.md`); the chart builder only validates and carries its numbers, never recomputes a quantile, whisker or fence. `source.pointCount` is the group count and `source.transformSummary` is `boxplot(box_summary)`.

**REJECT** (server builder MUST NOT emit; client adapter MUST drop):

| Code | Condition |
|------|-----------|
| `SOURCE_SHAPE_MISMATCH` (builder) | The source table lacks the `box_summary` fixed columns; a `groupKey` is empty or repeated; `method` differs between rows or is not registered; `n < 1`, `n` or `excludedCount` or `outlierCount` non-integral or negative; the seven statistics are not finite or not in order; `outlierCount > n`; `outliers` is not a JSON array of finite numbers of length `min(outlierCount, 20)`; an outlier lies inside the whiskers or outside `[min, max]`; `outlierCount = 0` while a whisker does not reach `min` / `max`; more than `BOX_MAX_GROUPS` rows. The tool answer carries a `recoveryHint` naming `box_summary`. |
| `INVALID_PARAMETERS` (builder tool) | `xColumn`, `yColumn`, `series`, `seriesColumn`, `histogramMode`, `orientation`, `pieSliceMode`, `pieMaxSlices` or `requestedTimeRange` supplied with `kind: boxplot` (or with `intent: distribution` resolving to a box source). `TOO_MANY_REFERENCE_LINES` above 12 lines as for the series kinds. |
| `CHART_BOXPLOT_INVALID` (client) | The adapter mirrors the builder's local invariants on the payload (§4.2): the same relations between the numbers given, no recomputation of statistics. |

### 3.0h `kind: "heatmap"` — payload and REJECT rules

**Payload:** no `series[]`, no `y_reference_lines[]`. `heatmap` is an object with `rows` (1–`HEATMAP_MAX_ROWS` unique non-empty strings, the `seriesColumn` values in first-appearance order), `cols` (1–`HEATMAP_MAX_COLS` unique non-empty strings, the `xColumn` values in first-appearance order), `values` (`rows.length` arrays of `cols.length` entries, each a finite JSON number or `null`; at least one number), `valueLabel` (string: what a cell value is, the `yLabel` else the `yColumn` name) and `missingCount` (the number of `null` cells). `null` is an explicit missing combination or a null source value; it is **never** zero-filled, and it does not conflict with §3.3 inv. 3, which governs `series[].y` only. The producer is `build_chart_from_tabular_result` over a long table such as a two-key `group_metric` result (`xColumn` = column dimension, `seriesColumn` = row dimension, `yColumn` = cell value); the builder only pivots and carries the numbers, never aggregates. `source.pointCount` is the number of cells with data and `source.transformSummary` is `heatmap(<seriesColumn> × <xColumn>)`; `x_label` / `y_label` default to the two binding names.

**REJECT** (server builder MUST NOT emit; client adapter MUST drop):

| Code | Condition |
|------|-----------|
| `INVALID_MAPPING` (builder) | `xColumn`, `seriesColumn` or `yColumn` missing, or `series[]` supplied, with `kind: heatmap`. |
| `TOO_MANY_CATEGORIES` (builder) | More than `HEATMAP_MAX_ROWS` distinct `seriesColumn` values or more than `HEATMAP_MAX_COLS` distinct `xColumn` values (`details.dimension` says which). |
| `DUPLICATE_CELL` (builder) | The same (`seriesColumn`, `xColumn`) pair appears twice; aggregate first, the heatmap never does. |
| `Y_COLUMN_NOT_NUMERIC` (builder) | A present, non-null cell value is not numeric. |
| `HEATMAP_ALL_MISSING` (builder) | Every cell is missing. |
| `INVALID_PARAMETERS` (builder tool) | `yReferenceLines`, `orientation`, `histogramMode`, `pieSliceMode` or `pieMaxSlices` supplied with `kind: heatmap`. |
| `CHART_HEATMAP_INVALID` (client) | The adapter mirrors the builder's local invariants on the payload (§4.2): the same relations between the values given, no regrouping. |

### 3.0e History overlay (`build_history_overlay_chart`)

Producer: **`build_history_overlay_chart`** (`docs/agent/history-overlay-chart.md`). Replaces the retired model-facing **`build_period_over_period_chart`** and **`build_multi_series_history_chart`** tools.

**Absolute-time mode** (tool `xAxisMode`: **`absolute_time`**, or omitted when all series share the same resolved window):

| Rule | Requirement |
|------|-------------|
| `kind` | **`line`** or **`scatter`** |
| `xAxisMode` | **MUST NOT** be present (absolute ISO time X) |
| `requested_time_range` | **SHOULD** be present when all series share the same resolved window |
| `series[].x[]` | ISO-8601 UTC timestamps from platform history |
| `series[].sourceWindow` | **MUST NOT** be present |
| Missing series | Partial chart allowed; **`source.missingSeries[]`** lists zero-sample series; hard fail when all series empty |

**Elapsed-time mode** (tool `xAxisMode`: **`elapsed_time`**, or server default when windows differ):

| Rule | Requirement |
|------|-------------|
| `kind` | **`line`** or **`scatter`** |
| `xAxisMode` | **`"elapsed"`** |
| `elapsedDomain` | **Required**; `end` = **max** resolved series duration in seconds (series may differ in duration; shorter series stop earlier) |
| `requested_time_range` | **MUST NOT** be present |
| `series[].x[]` | Non-negative integer **seconds** elapsed from that series' resolved window **start** (stringified) |
| `series[].sourceWindow` | **Required** per series with data: `start`, `end` (ISO UTC), `thingName`, `propertyName`, `periodLabel`, optional `resolvedTimeZone`, `sampleCount` (emitted), optional `rawSampleCount`, optional `omittedSampleCount` |
| Mixed Things / durations | **Allowed** on one elapsed chart (cross-Thing same shifted window, unequal durations) |
| Missing series | Partial chart allowed; **`source.missingPeriods[]`** lists zero-sample elapsed series |

**Normalized-time mode** (tool `xAxisMode`: **`normalized_time`** — explicit model input only):

| Rule | Requirement |
|------|-------------|
| `kind` | **`line`** or **`scatter`** |
| `xAxisMode` | **`"normalized"`** |
| `normalizedDomain` | **Required**; **MUST** be `{ "start": 0, "end": 1 }` |
| `elapsedDomain` | **MUST NOT** be present |
| `requested_time_range` | **MUST NOT** be present |
| `series[].x[]` | Numeric **fraction of window** in **`[0, 1]`** (stringified); producer **SHOULD** clamp to `[0, 1]` |
| `series[].sourceWindow` | **Required** per series with data (same fields as elapsed mode) |
| Mixed Things / durations | **Allowed** — intended for different-duration shape comparison |
| Missing series | Partial chart allowed; **`source.missingPeriods[]`** lists zero-sample series |
| Zero-duration window | Producer **MUST NOT** emit; tool fails **`HISTORY_OVERLAY_INVALID_TIME_WINDOW`** |

**Shared (all modes):**

| Rule | Requirement |
|------|-------------|
| Series count | **2..6** series, same top-level `propertyName` |
| Point budget | **`HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS = 5000`**; fair per-series uniform subsample when exceeded; `source.truncationApplied=true` |
| `y_reference_lines` | **MAY** be present (cap **12**); tool input `yReferenceLines` |
| `source.sourceResolved` | **`history_overlay`** |

Reference client (`chart-draw.js`): elapsed mode uses **`elapsedDomain`** and **`m:ss`** ticks; normalized mode uses **`normalizedDomain`** and **percent** ticks; absolute mode uses ISO time formatting per [`times-solution.md`](../docs/architecture/times-solution.md).

**Historical note:** §3.0e/§3.0f in releases before the history-overlay-chart topic named separate PoP and multi-series producers; those model-facing tools are retired.

### 3.0b `kind: "pie"` — payload and REJECT rules

**Payload:** Reuses **`series[]`** with **exactly one** series: `series[0].x[]` are slice labels (categories), `series[0].y[]` are non-negative numeric **values** (same length as `x`). `series[0].name` is the legend group label (e.g. the measured dimension). `requested_time_range` **MUST** be ignored for **`pie`**.

**REJECT** (server builder **MUST NOT** emit wire `ChartBlock`; client adapter **MUST** drop invalid blocks):

| Code | Condition |
|------|-----------|
| `PIE_REQUIRES_SINGLE_SERIES` | `series.length != 1`. |
| `ROW_ALIGN_FAILED` | `len(series[0].x) != len(series[0].y)`. |
| `Y_COLUMN_NOT_NUMERIC` | Any `y` not a finite number. |
| `PIE_NEGATIVE_VALUE` | Any `y < 0`. |
| `PIE_ZERO_TOTAL` | Sum of all `y` is zero (no drawable pie). |
| `TOO_MANY_SLICES` | Non-zero slice count exceeds `PIE_HARD_MAX_SLICES` when `pieSliceMode` is `all_nonzero` (no silent merge). |
| `DUPLICATE_SLICE_LABEL` | Two or more **non-zero** rows share the same `xColumn` category string (no silent merge). |

Slice policy parameters (`pieSliceMode`, `pieMaxSlices`) are tool arguments on **`build_chart_from_tabular_result`**, not fields on **`ChartBlock`**.

**Grouped bar (long table, `seriesColumn` set):** duplicate `(xColumn, seriesColumn)` row pairs **MUST** fail with **`DUPLICATE_SERIES_CATEGORY`** even when `y` values are identical (no silent dedupe).

**Line/scatter long pivot (`seriesColumn` set, `kind` `line` or `scatter`):** pivot long-format rows into multiple `series[]` entries (one per distinct `seriesColumn` value). Series order **MUST** follow **first-seen** row order in the source table. Duplicate `(xColumn, seriesColumn)` row pairs **MUST** fail with **`DUPLICATE_SERIES_CATEGORY`**. Missing `(xColumn, seriesColumn)` combinations **MUST NOT** be zero-filled (unlike grouped bar): each series' `x[]`/`y[]` contain only rows present for that series — line/scatter pivot stays **sparse** (no bar-style zero-fill grid). The reference client connects available points per series with straight segments and point markers for **`line`**, or markers only for **`scatter`**; interior gaps are **not** broken segments — the client draws one continuous polyline (or marker path) through each series' available points without synthesizing zero-fill or visible segment breaks for missing `(xColumn, seriesColumn)` pairs. At most **`MAX_SERIES = 6`** distinct series names; excess **MUST** fail with **`TOO_MANY_SERIES`**. `source.transformSummary` **SHOULD** be `long_table_pivot(seriesColumn)`; `source.filledMissingCombinations` applies to **bar** long pivot only.

**Zero-value categories:** Before applying `pieMaxSlices` / top-N / **`Other`**, categories with **`y === 0`** **MUST NOT** consume slice slots, **MUST NOT** be merged into **`Other`**, and **MUST NOT** be drawn as arcs. The count of such categories **SHOULD** appear in `source.zeroValueCategoryCount` when `source` is present.

### 3.0c `requested_time_range` (optional)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `start` | string | yes | Inclusive interval start, **ISO 8601** instant (UTC `…Z` recommended). |
| `end` | string | yes | Inclusive interval end, same format. |

**Semantics**

- Emitters **SHOULD** set this when the underlying query used an explicit time window (e.g. **`query_property_history`** on a numeric property with `startTime`/`endTime`), so the chart X axis matches **user intent** even when samples exist only in a sub-interval.
- **Clients MUST NOT** synthesize data points to fill gaps inside this window.
- If `requested_time_range` is **absent** or **invalid**, time **`line`/`scatter`** fall back to **data extent** for X (unchanged legacy behavior).

### 3.0d `build_chart_from_tabular_result` tool success JSON (v1, agent → LLM)

This is **not** a `type: "chart"` wire frame; it is the structured tool result string. For v1 reference **`parler-agent`**, **`CHART_EMITTED`** success payloads **SHOULD** mirror **`ChartBlock.source`** so the model can cite provenance and truncation honestly:

- Nested **`source`**: same field set as **`ChartBlock.source`** (including **`sourceResolved`**, optional **`sourceCacheId`**, **`sourceColumns`**, **`rowCount`**, **`pointCount`**, **`truncationApplied`**, optional **`filledMissingCombinations`**, **`zeroValueCategoryCount`**, **`transformSummary`**).
- Top-level convenience mirrors: at minimum **`sourceResolved`**, **`sourceColumns`**, **`rowCount`**, **`pointCount`**, **`truncationApplied`**, and legacy **`truncated`** (same boolean as **`truncationApplied`**).
- **`chartBlock`** (object, optional on persisted Stream tool rows): full **`ChartBlock`** payload as emitted on the live **`type: "chart"`** wire (minus transport envelope). **`AgentMessageStreamHistoryExporter`** hydrates **`ai-parler-history-v1`** assistant **`charts[]`** from this field when present; legacy rows without **`chartBlock`** are skipped for tabular charts (numeric-history tool results remain reconstructable from **`points[]`**).

### 3.1 `y_reference_lines[]` item

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `y` | number | yes | Value on the same scale as `series[].y`. |
| `label` | string | no | Short label (e.g. `435`, `USL`). |
| `role` | string | no | `usl` \| `ucl` \| `lcl` \| `lsl` \| `target` \| `limit` \| `warning`. **Spec:** `usl` / `lsl`. **Control:** `ucl` / `lcl`. **Center:** `target`. **Generic ceiling/floor:** `limit`. **Advisory:** `warning`. Default styling: `limit`. |

Reference lines extend the Y domain when needed so limits stay visible if all samples are on one side.

### 3.2 `series[]` item

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | yes | Legend label |
| `x` | string[] | yes | X values; absolute time series: **ISO 8601 UTC** (`...Z`); elapsed history overlay: **non-negative integer seconds** (stringified); normalized history overlay: **numeric fraction in [0, 1]** (stringified). |
| `y` | number[] | yes | Y values; **same length** as `x`. |
| `sourceWindow` | object | no | **Elapsed history overlay series:** resolved window metadata (see **§3.0e**). Clients **MAY** ignore. |

### 3.3 Invariants

1. For every series, `len(x) == len(y)`.
2. `x` order matches the source (e.g. ascending time for history).
3. Only **finite numeric** scalars in **`y`** (Thingworx: `NUMBER`, `INTEGER`, `LONG`, `BOOLEAN` as 0/1). **Emitters** MUST NOT emit a **`ChartBlock`** when any mapped `y` is missing, null, non-numeric, or non-finite: fail the tool/build step with structured errors (`Y_COLUMN_NOT_NUMERIC`, `ROW_ALIGN_FAILED`, …) per [`flexible-chart-solution.md`](../docs/architecture/flexible-chart-solution.md) §§8–9 — **no** row skipping or silent dropout in the server chart builders. **Clients** MUST reject the **whole chart** when any `series[].y` value is not a **finite JSON number** (`null`, missing slot, boolean, string — including numeric strings — `NaN`, `Infinity`): the reference adapter (`asChartBlock` in `wireAdapter.js`) drops the frame for **every kind and X mode**, and the renderer (`chart-draw.js`) additionally refuses to draw such a chart (defense in depth). Clients MUST NOT coerce strings or booleans to numbers; producer-side `BOOLEAN` → 0/1 mapping is not repeated on the client.
4. No downsampling in the **history read** path for lab-scale volumes; chart uses **all** numeric points in the window unless a future contract adds optional downsampling.
6. A **`histogram`** or **`heatmap`** block MUST NOT carry `series[]` or `y_reference_lines[]`; a **`boxplot`** block MUST NOT carry `series[]`; the other kinds MUST NOT carry a `histogram`, `boxplot` or `heatmap` object (§3.0f, §3.0g, §3.0h).
5. For **`line`** and **`scatter`**, every `x[]` value MUST be usable as **one consistent domain** for the whole chart: either all **finite numeric** (after string trim) or all **ISO-8601–parsable** instants (typical UTC `...Z`). **Categorical** X belongs in **`bar`**. Emitters MUST NOT rely on clients to coerce garbage strings to zero; [`flexible-chart-solution.md`](../docs/architecture/flexible-chart-solution.md) §9 aligns builder validation with the reference renderer.

### 3.4 Standalone `ChartBlock` example (e.g. REST block)

```json
{
  "kind": "line",
  "title": "line-1 — temperature",
  "x_label": "Time",
  "y_label": "Temperature",
  "series": [
    {
      "name": "line-1 / temperature",
      "x": ["2025-03-25T08:00:00Z", "2025-03-25T08:10:00Z"],
      "y": [41.2, 41.5]
    }
  ],
  "y_reference_lines": [{ "y": 435, "role": "limit", "label": "435" }]
}
```

---

### 3.5 `ChartGroupManifest` (C3b-1)

| Field | Type | Rule |
|-------|------|------|
| `groupId` | string | Non-empty; `g1` per request, same counter lifecycle as `chartId`. |
| `revision` | integer | ≥ 1; increases by one per resend; a lower or equal revision is ignored by clients. |
| `title` | string | As declared (1–120 chars). |
| `layout` | string | `auto` \| `stack` \| `grid`; a hint only, narrow hosts fall back to one column. |
| `final` | boolean | `true` only on the last revision of the group. |
| `members[]` | array | 2–6 objects in `order` `0..n−1`, fixed after declaration: `key` (unique, `^[a-z0-9_-]{1,32}$`), `order`, `name`, `expectedType: "chart"`, `state` ∈ `pending` \| `ready` \| `no-data` \| `error` \| `cancelled`; `ready` carries the downlinked `chartId` (unique within the group), `no-data` / `error` carry `code` (optional `message` ≤ 200 chars); other states carry neither. A member leaves `pending` exactly once. |
| `summary` | object | `expected` = `members.length`; `ready`, `noData`, `error`, `cancelled` equal the member counts; `final` equals the top-level flag. |
| `sharedCategories` | object | **C3b-2a, optional.** Present only when the group was declared with `sharedCategoryDimension`: `{ dimension: string, keys: string[] }` where `keys[i]` is the category key that owns palette slot `i`; at most **24** unique non-empty keys, **append-only** (a key never moves once assigned). A key is a category label normalised by trim + internal-whitespace collapse, case-sensitive: pie slice labels with a positive value (`Other` is its own key; zero slices take no key) and the series names of bar / line / scatter charts with two or more series. Other kinds and single-series charts contribute no key. |
| `members[].colorShared` | boolean | **C3b-2a, optional.** `true` when every key the member's chart contributed is in `sharedCategories.keys` (the client colours that chart by the shared slots); absent / `false` keeps the per-chart palette (no dimension declared, a kind without keys, or the 24-key cap would have been exceeded, in which case nothing is appended). |

Builder / tool codes: `INVALID_PARAMETERS` (`details.reason` ∈ `title` / `layout` / `members_count` / `member_key` / `duplicate_key` / `member_name` on declaration; `no_group_declared` / `unknown_member` / `member_not_pending` on `groupMemberKey`), `CHART_GROUP_LIMIT` (second declaration in one request). `no-data` is used for the frozen code set `EMPTY_AFTER_FILTER`, `HEATMAP_ALL_MISSING`, `CHART_FALLBACK`; every other failure code, including `PRESENTATION_ACTION_LIMIT`, is `error`.

**C3b-2a tool codes:** `INVALID_PARAMETERS` with `details.reason = shared_dimension` for a blank or over-long `sharedCategoryDimension`.

**REJECT (client, `CHART_GROUP_COLOR_INVALID`) — extension only:** `sharedCategories` not an object, an empty `dimension`, more than 24 keys, an empty or duplicate key, a non-boolean `colorShared`, or `colorShared: true` without `sharedCategories`. The client drops `sharedCategories` and every `colorShared` and keeps the group card with per-chart colours.

**REJECT (client, `CHART_GROUP_INVALID`):** any field rule above violated, a duplicate `key` or `chartId`, `order` not equal to the array position, a `ready` member without `chartId` or a non-ready member with one, a `no-data` / `error` member without `code`, or a `summary` that does not match the members. Only the group is dropped; its charts remain single cards.

## 4. Reference client (`parler-ui`): kinds, validation, ordering

### 4.1 Supported `kind` values

The reference UI accepts **`line`**, **`bar`**, **`scatter`**, **`pie`**, **`histogram`**, **`boxplot`**, and **`heatmap`** (`asChartBlock` in `wireAdapter.js`). New kinds require a **contract bump**.

**Palette slots (C3b-2a):** every series or slice colour is `resolveSeriesSlot(categoryText, fallbackIndex, colorKeys)` in `chart-theme.js`: inside a colour-shared chart-group member the category's index in `sharedCategories.keys` is its slot; otherwise the per-chart slot `chartSeriesSlot(fallbackIndex)` (byte-identical to the behaviour before chart groups). The same slot feeds the marks, the legend, the tooltip and keyboard read-out swatches, the data view and the print palette rewrite.

| `kind` | Use | Renderer (`chart-draw.js`) |
|--------|-----|----------------------------|
| **`line`** | Time series, elapsed/normalized history overlay, or ordered numeric X | X = **time** if **every** parsed `x` across **all** `series[]` is a date, else **numeric** linear. **Elapsed history overlay:** when `xAxisMode === "elapsed"` and **`elapsedDomain`** is valid, X domain = **`elapsedDomain.start`/`end`** (seconds); ticks **`m:ss`**; **`requested_time_range`** ignored. **Normalized history overlay:** when `xAxisMode === "normalized"` and **`normalizedDomain`** is valid, X domain = **`normalizedDomain.start`/`end`**; ticks **percent** (`0%`…`100%`); **`requested_time_range`** and **`elapsedDomain`** ignored. **Absolute time X:** if **`requested_time_range`** is set and parses, its **`start`/`end`** define the **X domain**; otherwise **data extent**. **Axis tick labels** use **`d3.scaleTime`** (browser-local wall clock) for datetime X. Y domain spans **all** series' Y plus reference lines. One polyline + small circles per series; **legend** when multiple series. **Sparse** regions inside the fixed domain remain empty (no padding with fake points). |
| **`scatter`** | Same payload as line | Same scales as **`line`**; **no** line paths; larger markers per series; legend when multiple series. |
| **`bar`** | Categories / buckets | **Grouped** bars when multiple series (shared category index; labels from **`series[0].x`**). Single series: **band** on indices, heights from **`y`**. **`orientation: "horizontal"`** swaps the axes: categories on the left axis in `series[0].x` order (first at the top, wrapped to at most two lines, ellipsized beyond), values on the bottom axis with the same signed domain and zero-baseline rule; the plot grows with the category count and scrolls inside the card. **`stackMode: "stacked"`** draws one stack per category spanning the whole band: positive values accumulate upward from zero and negative values downward (left / right when horizontal), the domain is the extreme of the per-category positive and negative sums through the signed-bar rule; **`stackMode: "percent"`** draws shares of the category total from every emitted series on a fixed `[0, 100]` axis and draws no bar for a zero-total category. Hiding a series (§4.3) removes its segment and closes the gap, but shares, denominators and the default domain are those of the full chart (`Fit Y axis to visible series` refits the signed domain to the visible sums). One query item per segment reads the series, category, value, share (percent) and the category total. Axis ticks: **categorical** `x` strings shown as-is (very long strings ellipsized); **ISO-8601** values matching `YYYY-MM-DDThh…` use the **`HH:mm:ss`** portion for compact time-axis labels. **`requested_time_range`** is **ignored**. |
| **`histogram`** | Binned distribution from `bin_numeric` | Linear numeric X over `edges`; one rectangle per bin drawn edge to edge (unequal widths stay unequal); height from `counts` or `densities` per `mode`; full width, `240` high; the X tick count is reduced, from the responsive count down to one tick, until the labels fit **as finally drawn**: each candidate is judged with the scale's own tick positions (d3 ticks rarely sit on the domain ends), the measured width of each formatted label and the anchor it ends up with (an end label that would leave the SVG is anchored inward, which moves it towards its neighbour), requiring every label inside the SVG and 8 px between neighbours; each smaller count keeps d3's own format, so neighbouring values stay distinct and nothing is abbreviated; when not even one tick fits, the axis shows none rather than a clipped one; bins, counts, densities and tooltip values are untouched; the value axis reads `Count` or `Density`; no legend, no series toggles, no X zoom; one query item per bin giving the interval (the last bin closed), count and density; data notes list method, valid, excluded, below- and above-range counts. **`requested_time_range`** is **ignored**. |
| **`boxplot`** | Five-number summaries from `box_summary` | Vertical; one band per group in source order (§4.6 band cap with one series slot, so few groups narrow the plot and it is centred); the value axis covers every `min` / `max`, listed outlier and reference line; box `q1`–`q3` with the median line, whiskers to `whiskerLow` / `whiskerHigh` with caps, hollow outlier markers; reference lines as on `bar`; plot area `240` high, plus the measured room below it for the rotated group labels (as on a vertical `bar`: labels are end-anchored at −35°, the bottom margin is tick + `widest label × sin 35°` + glyph height × cos 35° + the caption line, never below 44 and at most 160, and the left margin grows when a long label would leave the SVG, at most to 40% of the card width; a label that still does not fit after both caps is ellipsized by **measured pixel width**, the measured text being the drawn text, while tooltip, keyboard query and data view keep the full name; when the band step is under `1.75 ×` the tick font size only every k-th label is drawn, first and last always, with every mark, tick and query point kept; the axis caption is drawn below the labels); no legend, no series toggles, no X zoom; one query item per group (n and the five numbers, whiskers, listed outliers and "another N not shown") and one per listed outlier; data notes list the method, the group count, the summarised and excluded counts and the outlier totals. |
| **`heatmap`** | Row × column matrix from a two-key long table | Row labels on the left (the horizontal-bar category gutter: wrapped to two lines, or one when the cell is shorter, clamped), column labels below (horizontal when they fit the cell, else rotated and thinned with the first and last always kept, never scaled). Cell width `clamp(floor(innerW / cols), 20, 64)`, height `clamp(round(width × 0.62), 20, 40)`; in the card the plot scrolls horizontally when the cells exceed the content width and vertically above 720 px; the expand layer keeps horizontal scrolling with no height cap; print is the only context that lifts the 20 px floor: `W_print = min(W, 760)`, width `max(4, floor(innerW / cols))`, height by the same ratio clamped to `[8, 40]`, so every column fits the page; in every context the row height is additionally never below one row-label line (`categoryLabelLineHeight(tickSize)`, 16 px at the default 12 px tick font), because every row keeps its label and a shorter row makes neighbouring labels overprint. Colour from the existing Theme with no new token: all-non-negative or all-non-positive values → sequential (surface → series slot 0, realised as rising opacity of the slot colour), values crossing zero → diverging symmetric about 0 (slot 1 ← surface → slot 0), a constant matrix → the midpoint colour; missing cells hatched in the grid colour and read as `No data`; cells at least 40 × 24 show their value. Cells carry `data-parler-palette-role="heat"` with `data-heat-t` / `data-heat-scale`, which the print rewriter uses to recompute the fill from the print palette. The legend is a colour bar with min / max (and 0 when crossing zero) ticks, the value label and a `No data` sample. No series legend, series toggles or X zoom; one query item per cell (row, column, value or `No data`), arrows move along a row and between rows. |
| **`pie`** | Composition / share of whole | **Exactly one** `series`; `x[]` = slice labels, `y[]` = non-negative values; D3 `pie`/`arc`; slice colors stable by label; native **`<title>`** on each slice arc (label, raw value, percent); 0-value slices draw no arc but **MAY** appear in legend when space allows. **`requested_time_range`** is **ignored**. |

**Y reference lines:** invalid entries **skipped**; valid subset kept (`asYReferenceLines`). Roles map to stroke styles in the reference theme.

### 4.2 Validation (wire → UI)

The adapter **drops** the frame (no `assistant.chart`) if:

- `chart.kind` is not `line` / `bar` / `scatter` / `pie` / `histogram` / `boxplot` / `heatmap`.
- For **`heatmap`** (`CHART_HEATMAP_INVALID`): `series`, `y_reference_lines`, a `histogram` or a `boxplot` object present; `heatmap` missing or not an object; `rows` not 1–24 or `cols` not 1–48 unique non-empty strings; `values` not `rows.length` arrays of `cols.length` entries; any entry not a finite JSON number or `null` (`undefined`, strings, `NaN` are rejected); no numeric cell at all; `missingCount` not equal to the `null` count; `valueLabel` not a string. A `heatmap` object on any other kind is rejected the same way. Fixed X modes and `orientation` are rejected with their own codes.
- For **`boxplot`** (`CHART_BOXPLOT_INVALID`): `series` or a `histogram` object present; `boxplot` missing or not an object; `method` not `tukey_1_5_iqr_linear_p_v1`; fewer than 1 or more than 24 groups; a group with an empty or repeated `key`; `n` not a positive integral number or `excludedCount` / `outlierCount` not non-negative integral; any of the seven statistics not finite or not in the order `min ≤ whiskerLow ≤ q1 ≤ median ≤ q3 ≤ whiskerHigh ≤ max`; `outlierCount > n`; `outliers` not an array of finite numbers of length `min(outlierCount, 20)`; an outlier inside the whiskers or outside `[min, max]`; `outlierCount = 0` with a whisker short of `min` / `max`. A `boxplot` object on any other kind is rejected the same way. Fixed X modes and `orientation` are rejected with their own codes.
- For **`histogram`** (`CHART_HISTOGRAM_INVALID`): `series` or `y_reference_lines` present; `histogram` missing or not an object; `edges` not a strictly increasing finite array of length `counts.length + 1`; fewer than 1 or more than 50 bins; any count or scalar count not a non-negative integral finite number; `Σcounts ≠ validCount − belowRangeCount − aboveRangeCount` or `Σcounts = 0`; `densities` not the same length or any entry differing from `counts[i] / (Σcounts × width)` by a relative error above `1e-9` (an empty bin must be exactly `0`); `mode` not `count` / `density`; `method` not `explicit_edges_v1` / `equal_width_v1`. Any other kind carrying a `histogram` object is dropped with the same code.
- For the series kinds: `chart.series` is missing, not an array, or **empty**.
- Any `series[]` entry is not an object, or its `x` / `y` is not a non-empty array, or `len(x) !== len(y)` (§3.3 inv. 1). One invalid series rejects the whole chart; valid series MAY still differ in point count from each other.
- Any `series[].y` value is not a **finite JSON number** — `null`, missing slot, boolean, string (including numeric strings), `NaN`, `Infinity` (§3.3 inv. 3). Applies to `line`, `bar`, `scatter` and `pie` in absolute, numeric, elapsed and normalized X modes alike; no client-side coercion.
- For **`pie`**: `series.length !== 1`, any `y < 0`, or sum of `y` is **0** (defense in depth; see **§3.0b**).
- `orientation` is present and either `chart.kind` is not `bar` or the value is not exactly `"vertical"` / `"horizontal"` (no case folding, no coercion).
- `stackMode` is present (`CHART_STACK_MODE_INVALID`) and either `chart.kind` is not `bar`, the value is not exactly `"stacked"` / `"percent"` (`"grouped"` is never on the wire), fewer than two series are given, or the value is `"percent"` and any `series[].y` is negative.

The same adapter validates `ai-parler-history-v1` `charts[]` on hydration, so exported history that carries an invalid `ChartBlock` loses that chart only; sibling charts, tables and text in the row are kept. The reference client logs a diagnostic with a stable reason code (`CHART_KIND_INVALID`, `CHART_SERIES_EMPTY`, `CHART_SERIES_SHAPE`, `CHART_Y_NOT_FINITE_NUMBER`, `CHART_PIE_SERIES_COUNT`, `CHART_PIE_NEGATIVE`, `CHART_PIE_ZERO_TOTAL`, `CHART_FIXED_DOMAIN_INVALID`, `CHART_ORIENTATION_INVALID`, `CHART_HISTOGRAM_INVALID`); these codes are not wire fields and no placeholder artifact is emitted.

Unknown **top-level** wire fields: **ignored**.

### 4.3 Ordering vs other frames

- **`chart`** may be sent **before**, **between**, or **after** **`content.delta`** for the same **`request_id`**.
- UI **MUST** attach charts to the **assistant row** for that **`request_id`** ([`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) §4).
- **`activity`** / **`content.delta`** do not replace **`chart`**; a turn may be text-only, chart-only, or both.

---

## 5. Who emits a `chart` frame? (LLM vs Agent)

| Layer | Role |
|-------|------|
| **Agent / orchestration** | **Owns** emission of **`type: "chart"`**. **SHOULD** emit when a **validated `ChartBlock`** exists (tool results, history, ThingWorx samples). **MUST NOT** use model free-text alone as the only source of coordinates. |
| **LLM** | May call tools or steer intent; may emit Markdown in **`content.delta`**. Does **not** invoke **`ReceiveMessage`** or append wire frames. |
| **Tools** | Return **structured** data; **agent code** maps to **`ChartBlock`** and sends the wire frame. |

**Recommended flow:** (1) Model selects *what* to visualize. (2) Server builds and validates **`ChartBlock`**. (3) Server emits **`{ "type": "chart", "request_id", "chart", … }`** (+ **`conversation_id`** on AlwaysOn).

**Anti-patterns:** Expecting wire JSON inside assistant text; relying on huge **`activity.message`** JSON without a **`chart`** frame (truncation risk).

---

## 6. UI rendering notes (portable)

- **Line:** connect `(x,y)` in order.
- **Bar:** one bar per index; `x` labels categorical or time strings. `orientation: "horizontal"` draws the same bars along the value axis at the bottom with categories down the left; data roles do not change. `stackMode` stacks the same series per category (signed, or as shares of the category total); the values in `series[]` are unchanged and the shares are a client presentation computation.
- **Scatter:** points only (same payload shape as line).
- **Histogram:** adjacent rectangles between consecutive `edges`; height from `counts` or `densities` as `mode` says; nothing is re-binned.
- **Boxplot:** one band per group; box between `q1` and `q3`, median line, whiskers to the whisker values with caps, hollow outlier markers at the listed values; no statistic is recomputed and the outliers not listed are only counted.
- **Heatmap:** one rectangle per cell in the row / column order given; colour by the §4.1 rule from the finite values only; `null` cells hatched; nothing is regrouped, aggregated or zero-filled.
- **Y reference lines:** full-width horizontal segments; style **MAY** vary by `role` (this repo: spec/limit red solid, control orange dashed, target green dashed, warning yellow dashed).

Frontends may use D3, Chart.js, ECharts, etc., if they honor this contract.

---

## 7. Reference client limitations (disclosure)

- **`drawChart`** renders a chart only when **every** `series[]` entry passes the §4.2 shape and finite-number Y rules; a single invalid series rejects the whole chart (no partial drawing). **`line` / `scatter`** use **union** Y across series; **X** for datetime charts uses **`requested_time_range`** when valid, else **union** data extent for X. Mixed date vs numeric X across series is unsupported — parsing must yield a consistent scale type.
- **Bar** with multiple series uses **grouped** bars; category count is the **minimum** length across series if lengths differ. X ticks follow the §4.1 **`bar`** row (categorical vs ISO datetime heuristic); non–ISO-like strings are not forced through a time-only slice. Horizontal bars keep the category order top-down, wrap category labels to two lines, and scroll inside the card when the category count makes the plot taller than the card viewport; print shows the full height.
- **Histogram** draws the `mode` array as given; the other array is only shown in the query tooltip and data notes. Switching `count` / `density` on the client is not offered.
- **Boxplot** is vertical only and shows at most the 20 listed outliers per group; a horizontal boxplot and a notched or mean-marked box are not offered.
- **Heatmap** offers one colour rule derived from the Theme (no second palette, no user-chosen scale), no correlation-matrix semantics, no pagination of wide matrices (they scroll on screen and shrink only in print), and no client-side sorting of rows or columns.
- **Stacked bars** are requested only by the explicit `stackMode` parameter (no intent stacks on its own); percent stacking accepts non-negative values only, and no stacked line / area, diverging-centre stacking or per-segment labels are offered.
- **Pie** uses **exactly one** series; arcs are drawn only for strictly positive **`y`** values (zero categories are skipped). Invalid pie payloads are **dropped** by `asChartBlock` (defense in depth; see **§4.2**).

These are **UI** behavior notes, not permission to shrink the **wire** schema.

---

## 8. Versioning

- **Every edit:** any commit that edits this normative document bumps **[`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md)** in the same commit; there is no wording-only exception.
- **Additive:** new optional **`ChartBlock`** fields or new **`kind`** values also update the aligned implementation/tests (and **`API_CONTRACT.md`** if the wire `chart` frame changes) in that commit.
- **Breaking:** align the implementation/tests, **`API_CONTRACT.md`**, this file, and the bundle patch in the same commit.
- **AlwaysOn** top-level requirements on **`ReceiveMessage`** → [`agent-alwayson.md`](../docs/architecture/agent-alwayson.md).
