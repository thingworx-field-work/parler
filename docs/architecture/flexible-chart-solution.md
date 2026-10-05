# Flexible Chart — tabular chart build specification

**Status:** Implemented. This document is the **normative execution spec** for the core **`build_chart_from_tabular_result`** tabular semantics (`line` / `bar` / `scatter`). For overlay history charts see **`docs/agent/history-overlay-chart.md`**; for **`intent`** mode see **`docs/agent/chart-intent.md`**; for **`kind: pie`**, grouped bar (**`seriesColumn`**), and chart-rescue §7.x see **`docs/agent/prompt-to-chart.md`**. The tool also accepts `histogram`, `boxplot` and `heatmap` kinds, which are specified in [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md).  
**Scope:** General charting for **Data Insights**: turn **real tabular results** plus **explicit chart declarations** into a uniform [`ChartBlock`](../../CONTRACTS/CHART_CONTRACT.md), delivered on the existing **`type: "chart"`** path (web, terminal, history replay).

Background: [`flexible-chart.md`](../ui/flexible-chart.md).

**Related specs:** [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md), [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md), [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md).

---

## 1. Basic principles

### 1.1 Chart content comes from real data

**Chart content still comes from real data; the LLM only declares how to draw.**

- The model **must not** submit `series[].x` / `series[].y` coordinate arrays as authoritative chart truth for real business use.  
- The **server** reads columns from a locatable tabular result, validates, and assembles `ChartBlock`.  
- The **client** only renders valid `ChartBlock`; it does not infer charts from free text.

### 1.2 Data plane vs chart-declaration plane

| Plane | Meaning |
|------|------|
| **Data plane** | **Real tables** from `invoke_service` / list queries / paged fetches (rows, column names, cell values, `cacheId`, etc.). |
| **Chart-declaration plane** | Fields the model supplies to the tool only: `source`, `kind`, `xColumn`, `yColumn` or `series[]`, `title`, `xLabel`, `yLabel`, `yReferenceLines`. |

### 1.3 Not “smart BI”

The builder is **not** an auto-visualization engine: no implicit aggregate / pivot, no dashboard (kind selection from an **`intent`** is a separate, bounded mode — `chart-intent.md`).  
The core deliverable:

**Real table + explicit declaration → validation → `ChartBlock` (or structured error).**

---

## 2. Core decisions (fixed)

1. **`kind` (or `intent`) must be explicit** — the server does not infer a default chart kind; exactly one of `kind` or `intent` is required.  
2. **`source` is only two kinds** — `last_invoke`, `cache_id`.  
3. **`last_invoke` strict policy** — if the current user turn has **>1** “chart-eligible candidate” (§6) and the model still passes `last_invoke` → **`AMBIGUOUS_LAST_INVOKE`**, require `cacheId` instead.  
4. **Row-alignment failure fails the whole chart** — do not skip bad rows; unified **`ROW_ALIGN_FAILED`** (or all-Y non-numeric per §8).  
5. **Chart contract unchanged** — output remains `ChartBlock`, wire remains `type: "chart"`.  
6. **Success / failure envelope unified** — success: `status: "success"` + payload; failure: `status: "error"` + `code` + `message` + optional `details`.

---

## 3. Scope: in / out

### 3.1 In scope

- General **chart builder** (table + declaration → `ChartBlock` or error).  
- Data sources: `source === "last_invoke"` | `"cache_id"` (`cache_id` requires `cacheId`).  
- Chart kinds covered here: `line` | `bar` | `scatter` (per `CHART_CONTRACT`); other kinds are listed in the header.  
- Two mapping modes: **single X single Y** (`xColumn` + `yColumn`); **single X multi-series** (`xColumn` + `series:[{name,yColumn}]`), **mutually exclusive**.  
- Validation: resolvable source, tabular result, columns exist, Y numeric, series count, row cap, reference-line count, row alignment, **X typing rules** (§9).  
- Stable error codes (§8).  
- Coexists with **`query_property_history` auto chart** (§13).

### 3.2 Out of scope

`kind` inference without an `intent`, implicit aggregate / group by / pivot, dual axis, area charts, subplots, complex statistical overlays, model-supplied point values replacing tables.

---

## 4. Tool: `build_chart_from_tabular_result`

### 4.1 Meaning

- Input is an **existing, locatable tabular result**, not “the model fabricates points on the fly”.  
- This tool **does not** infer `kind` (an **`intent`** is resolved to a kind by fixed server rules — `chart-intent.md`).

### 4.2 Parameters (LLM / schema side: **camelCase**, same as `fetch_cached_result`)

**Required**

| Field | Type | Description |
|------|------|-------------|
| `source` | string | Literal `last_invoke` or `cache_id` |
| `kind` | string | `line` \| `bar` \| `scatter` (see header for other kinds); alternatively `intent` |

**Conditionally required**

| Field | Condition |
|------|------|
| `cacheId` | Required when `source === "cache_id"` |

**Mapping (choose one; both required)**

| Shape | Required fields | Forbidden |
|------|----------|------|
| **A — single Y** | `xColumn`, `yColumn` | Must not include `series` |
| **B — multi Y** | `xColumn`, `series:[{name,yColumn}]` | Must not include `yColumn` |

**All of the following → `INVALID_MAPPING`:**

- Both `yColumn` and `series` present;  
- **Neither A nor B satisfied** (missing `xColumn`, or missing both `yColumn` and `series`, or empty `series`, or a `series` entry missing `name`/`yColumn`).

**Optional**

`title`, `xLabel`, `yLabel`, `yReferenceLines` (same structure as `CHART_CONTRACT` §3.1, same `role` enum), `orientation` (`vertical` | `horizontal`; only when the explicit or intent-resolved kind is `bar`, otherwise **`INVALID_PARAMETERS`**; the builder writes `ChartBlock.orientation` only for `horizontal`).

### 4.3 Example

```json
{
  "source": "cache_id",
  "cacheId": "cache-123",
  "kind": "line",
  "xColumn": "timestamp",
  "series": [
    { "name": "Temperature", "yColumn": "temperature" }
  ],
  "title": "SteamSensor Temperature Trend",
  "xLabel": "Time",
  "yLabel": "Temperature (°C)",
  "yReferenceLines": [{ "y": 425, "role": "limit", "label": "425" }]
}
```

---

## 5. `source` semantics

### 5.1 `cache_id`

- `cacheId` is required.  
- Server resolves in the **current session** large-result cache (**same key space** as **`InvokeServiceExecutor` / `fetch_cached_result`**).  
- Cache entry missing → **`CACHE_MISS`** (see §7 ordering).

### 5.2 `last_invoke`

- Points to the **logical table** for the **last in time** **qualifying success** within the **current ThingWorx session + agent turn triggered by the current user message** (§6).  
- If this turn has **≤0** qualifying successes → **`SOURCE_RESULT_NOT_TABULAR`**, suggest `details.reason` = `no_qualifying_tabular_tool`.  
- If **>1** qualifying success and `last_invoke` is still used → **`AMBIGUOUS_LAST_INVOKE`**, `details.candidateCount`.

### 5.3 Large responses and `sampleRows`

If the tool returns a **large** shape (`sampleRows` + `cacheId` only, full payload in cache):

- **Logical table** = cache object for `cacheId`; **forbidden** to chart from `sampleRows` alone.  
- If `cacheId` is missing or cache unreadable → apply §7 → `CACHE_MISS` or `SOURCE_RESULT_NOT_TABULAR`.

---

## 6. Qualifying tools (`last_invoke` counting and resolution)

These **built-in** tools, after a **successful** return, if the parsed result satisfies §7 “tabularizable”, count as one **qualifying** call this turn and update the “last qualifying logical table” pointer:

| Tool | Notes |
|------|------|
| `invoke_service` | Only when the body normalizes to a row table (including large + resolvable `cacheId`) |
| `query_alert_summary`, `query_alert_history` | Same envelope as `invoke_service` |
| `query_entities`, `query_entities_by_taxonomy` | List / query result is a table |
| `list_entities_by_type` | Metadata list is a table |
| `fetch_cached_result` | Paged row set is a table (still tied to original `cacheId` data shape) |
| `tabulate_cached_result` | Tabulated rows |
| Extended tools | When they return the same success envelope as `invoke_service` |

**Explicitly not qualifying:**

- **`query_property_history`** — uses dedicated **auto `type:"chart"`**, JSON is `points`-specific shape, **does not** participate in `last_invoke` ambiguity counting; it is not a source for this builder.  
- **`build_chart_from_tabular_result`** — output is not a table for follow-on charting.  
- Other calls returning scalars, errors, or non-tabularizable JSON — do not count.

**Implementation:** `TabularChartRoundState` keeps, per user turn:

- `qualifyingTabularSuccessCount`,  
- the latest qualifying table (a `cacheId` in **the same session cache**, or inline rows),  

Reset count and pointer at the **start of each new user message**.

---

## 7. Tabularization and error ordering

### 7.1 Tabularization

Sources accepted by the builder must normalize to a **row table**: one record per row, column names index cells (same as existing INFOTABLE→JSON convention).  
Summary-only, no consumable rows, or not a table at all → **`SOURCE_RESULT_NOT_TABULAR`**.

### 7.2 `CACHE_MISS` vs `SOURCE_RESULT_NOT_TABULAR`

For **`source = cache_id`**, in **fixed order**:

1. **`cacheId` missing** (should already have failed `MISSING_CACHE_ID` earlier)  
2. **`cacheId` not in session cache** → **`CACHE_MISS`**  
3. **Entry exists but cannot be interpreted as row table** (e.g. corrupted internal shape) → **`SOURCE_RESULT_NOT_TABULAR`**, `details.reason` = `cache_entry_not_tabular`  

For **`last_invoke`**:

1. Resolve pointer first; **no qualifying** → `SOURCE_RESULT_NOT_TABULAR`  
2. **Multiple qualifying** + `last_invoke` → `AMBIGUOUS_LAST_INVOKE`  
3. Pointer valid but table open fails → `CACHE_MISS` or `SOURCE_RESULT_NOT_TABULAR` as appropriate (same as invoke shape)

---

## 8. Success and failure envelopes

### 8.1 Success

```json
{
  "status": "success",
  "code": "CHART_EMITTED",
  "chartId": "…",
  "kind": "line",
  "seriesCount": 1,
  "pointCount": 240,
  "sourceResolved": "cache_id",
  "truncated": false
}
```

- The full field list (including the `chartBlock` copy and the provenance mirrors `source`, `truncationApplied` / `truncated`) is normative in [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md) §3.0d. The authoritative chart for the user is the **`type:"chart"`** wire.  
- Oversized tables are not truncated: beyond the §10 row cap → **`ROW_OR_PAYLOAD_LIMIT`**.

### 8.2 Failure

```json
{
  "status": "error",
  "code": "COLUMN_NOT_FOUND",
  "message": "Column not found.",
  "details": {
    "column": "temperatur",
    "availableColumns": ["timestamp", "temperature"]
  }
}
```

`details` should be **filled when possible** for `COLUMN_NOT_FOUND`, `AMBIGUOUS_LAST_INVOKE` (`candidateCount`), etc., so the model can self-correct.

### 8.3 Error codes (minimum set)

| code | Typical trigger |
|------|----------|
| `MISSING_SOURCE` | No `source` or invalid enum |
| `MISSING_KIND_OR_INTENT` | Neither `kind` nor `intent` |
| `CHART_INVALID_PARAMETERS` | Both `kind` and `intent` |
| `UNSUPPORTED_CHART_KIND` | `kind` not a supported value (case normalization failed) |
| `MISSING_CACHE_ID` | `source=cache_id` with no `cacheId` |
| `CACHE_MISS` | §7.2 |
| `SOURCE_RESULT_NOT_TABULAR` | §7.1 |
| `INVALID_MAPPING` | §4.2 (incl. missing `xColumn`, empty `series`, `series` entries missing fields) |
| `PROTECTED_TABULAR_COLUMN_BLOCKED` | **`xColumn`**, **`yColumn`**, or **`series[].yColumn`** names a **`PASSWORD`** (`DataShape`) column — **`docs/agent/protection.md`** §4.7 |
| `COLUMN_NOT_FOUND` | Column name not in table |
| `Y_COLUMN_NOT_NUMERIC` | Y column fails §9.4 |
| `X_COLUMN_INVALID_LINE_SCATTER` | For `line`/`scatter`, X cell **has a value** but is neither a finite number nor parseable ISO time (**null / missing / whitespace-only** X per §9.4 row holes → `ROW_ALIGN_FAILED`) |
| `X_AXIS_TYPE_MIXED` | Same `line`/`scatter` chart mixes numeric and time axes on X |
| `TOO_MANY_SERIES` | `series.length` > `MAX_SERIES` (§10) |
| `TOO_MANY_CATEGORIES` | `bar` with more than `BAR_MAX_CATEGORIES` categories (§10) |
| `AMBIGUOUS_LAST_INVOKE` | §5.2 |
| `EMPTY_AFTER_FILTER` | No points after filter |
| `ROW_ALIGN_FAILED` | Row count mismatch, `bar` multi-series same-row X text mismatch (§9.3), or Y holes |
| `ROW_OR_PAYLOAD_LIMIT` | Rows > `MAX_CHART_ROWS` |
| `TOO_MANY_REFERENCE_LINES` | > `MAX_REFERENCE_LINES` |
| `CHART_BUILD_INTERNAL` | Uncategorized failure |

---

## 9. Validation by `kind` (server must implement)

### 9.1 Column names and whitespace

- **trim** before comparing column names; headers with spaces should match the model’s trimmed declaration.  
- If platform column names are case-sensitive, **document** it; recommend matching ThingWorx **case sensitivity**.

### 9.2 **`line` and `scatter` X column** (aligned with `parler-ui` `chart-draw.js`)

For **each row of `xColumn`** (shared across all series):

**Allowed cell semantics (pick one; consistent for the whole column):**

1. **Numeric axis:** after `trim`, `Double.valueOf` (or equivalent) **finite** number (including integers); **entire column** numeric.  
2. **Time axis:** after `trim`, **ISO-8601** parseable instant (same as existing history; UTC `...Z` recommended); **entire column** as time.

**Forbidden:**

- Arbitrary **category strings** (e.g. `"Shift A"`) as X for `line`/`scatter` — use **`kind=bar`**.  
- **Within one chart**, mixing “some rows numeric, some rows time strings” → **`X_AXIS_TYPE_MIXED`**.  
- Fits neither → **`X_COLUMN_INVALID_LINE_SCATTER`**.

The **server** must reject illegal X at validation; **must not** rely on the client coercing to `0` to “make a chart”.

### 9.3 `bar` X and multi-series

- **`xColumn`:** category axis; cells **stringified** for axis labels (same as current renderer: categories from **first series `x`**).  
- **Y:** numeric (same as `CHART_CONTRACT` §3.3).  
- **Multi-series (grouped bars):** for each row, **all series X cells** (same `xColumn`) **must be equal after pairwise trim**, else **`ROW_ALIGN_FAILED`** (matches `chart-draw` using first series for tick labels and row index alignment—avoids mislabeled bars).  
- **Duplicate categories** are not merged (current behavior).
- **Orientation:** `orientation: horizontal` changes only the emitted `ChartBlock.orientation`; category order, sorting, limits, `source` and every value are identical to the vertical build (`CHART_CONTRACT` §3).

### 9.4 Y numeric rules

- Allowed: `NUMBER` / `INTEGER` / `LONG` / `BOOLEAN` (0/1).  
- **Per column:** if any row of a `yColumn` cannot parse to a finite number → **`Y_COLUMN_NOT_NUMERIC`**.  
- **Per row:** if column types are legal but a row is missing / null (multi-series misaligned on same row) → **`ROW_ALIGN_FAILED`**.

### 9.5 Row alignment

- Single Y: `xColumn` row count = `yColumn` row count (same raw table rows before filter).  
- Multi Y: all `yColumn` and `xColumn` **same row count**, and for **bar**, X equal across series per row (§9.3).  
- Else **`ROW_ALIGN_FAILED`**.

### 9.6 Ordering

- **`line`:** **preserve source row order** (no silent sort by X).  
- **`bar`/`scatter`:** same — preserve source order (no sort).

---

## 10. Fixed caps

| Constant | Value | Notes |
|------|-----|------|
| `MAX_SERIES` | 6 | Max `series` entries |
| `BAR_MAX_CATEGORIES` | 24 | Max `bar` categories; beyond → `TOO_MANY_CATEGORIES` |
| `MAX_REFERENCE_LINES` | 12 | Same as `CHART_CONTRACT` |
| `MAX_CHART_ROWS` | 5000 | Max rows per chart; beyond → `ROW_OR_PAYLOAD_LIMIT` |

---

## 11. Client behavior (`parler-ui` / `chart-draw.js`)

- **`line` / `scatter`:** strict X validation per §9.2 before draw; **if any cell is illegal or axes mix**, **do not draw** (avoids old `parseX` turning bad strings into 0 and fake charts).  
- **`bar` multi-series:** same as current — **grouped bars**, `x` from each series same row; if cross-series same-row X text differs, **`console.warn`**, still draw by index (server should have returned `ROW_ALIGN_FAILED`; this is a safety net).

When rebuilding the ThingWorx monolith bundle: after editing `parler-ui/components/chart-draw.js`, run **`npm run build:tw`** (under `parler-ui/`) to refresh `parler-ui.tw.js`.

---

## 12. Implementation placement (`parler-agent`)

| Topic | Class |
|------|------|
| Tool registration | `BuiltInTools` |
| Executor | `BuildChartFromTabularResultExecutor` (schema: `BuildChartFromTabularResultToolSchema`) |
| Cache | **Same source** as `InvokeServiceExecutor` / `fetch_cached_result` |
| Turn state | `TabularChartRoundState` / `TabularChartRoundHooks`; source resolution `TabularChartSourceResolver` |
| Builder | `ParlerTabularChartBuilder` — pure “row table + declaration → `ChartBlock`” |
| Downstream | `ParlerReceiveMessageSupport#wireChart`, **`request_id`** matches current assistant turn |

---

## 13. Coexistence with `query_property_history`

- **Do not remove** **auto chart wire** after that tool succeeds.  
- **`build_chart_from_tabular_result`** mainly serves **`invoke_service`** and similar tabular results; routing docs explain which path to use.  
- `last_invoke` **does not** count `query_property_history` (§6).

---

## 14. Behavior matrix

| Case | Expected |
|------|------|
| `cache_id` + valid table + single Y + `line` | `success` + wire + valid `ChartBlock` |
| Missing `kind` (and no `intent`) | `MISSING_KIND_OR_INTENT` |
| `cache_id` missing `cacheId` | `MISSING_CACHE_ID` |
| Bad `cacheId` | `CACHE_MISS` |
| Cache object not tabularizable | `SOURCE_RESULT_NOT_TABULAR` (not `CACHE_MISS`) |
| Missing `xColumn` | `INVALID_MAPPING` |
| Both `yColumn` and `series` | `INVALID_MAPPING` |
| Wrong column name | `COLUMN_NOT_FOUND` + `availableColumns` |
| `line` X neither number nor ISO | `X_COLUMN_INVALID_LINE_SCATTER` |
| Same chart mixes numeric and time on X | `X_AXIS_TYPE_MIXED` |
| `bar` multi-series same-row X text mismatch | `ROW_ALIGN_FAILED` |
| Two invoke qualifying still `last_invoke` | `AMBIGUOUS_LAST_INVOKE` |
| Two series different row counts | `ROW_ALIGN_FAILED` |
| 13 `yReferenceLines` | `TOO_MANY_REFERENCE_LINES` |
| Rows > 5000 | `ROW_OR_PAYLOAD_LIMIT` |
| Large shape sample-only, cache not resolvable | `CACHE_MISS` or `SOURCE_RESULT_NOT_TABULAR` (per §7) |

---

## 15. Scenarios and prompts

**Good fit:** explicit chart kind, nameable columns, table just fetched; “turn the last service result into a bar chart”.  
**Poor fit:** “pick the prettiest chart”, implicit aggregates, dual-axis.

---

## 16. Bottom line

**Real table + explicit declaration → server validation (X/Y/alignment) → uniform `ChartBlock` → `type:"chart"`.**
