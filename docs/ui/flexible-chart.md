# Flexible charting: structured declaration + server-side chart build

**Status:** Background and product rationale for Parler charting. The tabular chart path
(`build_chart_from_tabular_result`) and the history overlay chart are implemented.

**Normative execution:** [`flexible-chart-solution.md`](../architecture/flexible-chart-solution.md) (tabular build), [`chart-intent.md`](../agent/chart-intent.md) (intent mode + baseline), [`prompt-to-chart.md`](../agent/prompt-to-chart.md) §7–8 (pie / grouped bar / rescue), [`history-overlay-chart.md`](../agent/history-overlay-chart.md) (overlay). **Wire:** [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md). **ThingWorx-side charting:** **`parler-agent/`** (`ParlerChartWireSupport`, `BuildChartFromTabularResultExecutor`, `query_property_history`).

---

## 1. Requirements

1. **Product experience**  
   Users want **renderable charts** in conversation, not only long text. Web (`<parler-ui>`) and terminal consume **one structured chart shape**.

2. **Gap addressed**  
   Auto **`type: "chart"`** wire from **`query_property_history`** covers numeric history only. Other **`invoke_service`** calls that return **INFOTABLE / row lists**, even when semantically time series or category–value tables, need a unified, safe chart path; otherwise the model can only describe them in prose or free-form JSON, with weak quality and verifiability.

3. **Principles**  
   - Keep **LLM structured output** (chart **intent**); **programs** build the chart.  
   - **Do not trust** model-fabricated business coordinate sequences as “real history”; values must trace to **tabular tool results**.  
   - **Dynamic results** (different services, column names) get charts **without** a bespoke Java mapper per service, through **one general, validatable** pipeline.

---

## 2. Current state

| Area | Behavior |
|------|-----------|
| **Client** | **`parler-ui`** validates and renders `ChartBlock` per [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md) (D3). |
| **Numeric history** | **`query_property_history`** success → **`ParlerChartWireSupport`** → `ChartBlock` → **`wireChart`**. |
| **Tabular / dynamic results** | **`build_chart_from_tabular_result`** reads qualifying tabular tool results or **`cacheId`**; server validates columns and builds `ChartBlock` — see [`flexible-chart-solution.md`](../architecture/flexible-chart-solution.md). **`kind: pie`**, grouped bar via **`seriesColumn`**, and chart-rescue flows: [`prompt-to-chart.md`](../agent/prompt-to-chart.md). |
| **History overlay** | **`build_history_overlay_chart`** — 2–6 series on one chart; see [`history-overlay-chart.md`](../agent/history-overlay-chart.md). |
| **Multi-chart per turn** | Multiple **`build_chart_from_tabular_result`** calls with distinct **`cacheId`** sources — [`multi-chart-and-thrashing-safeguards.md`](../agent/multi-chart-and-thrashing-safeguards.md) §4. |
| **Terminal** | Same `type: "chart"` / `ChartBlock` contract as Web; renderer may differ. |

---

## 3. Architecture

### 3.1 Data plane vs chart-declaration plane

| Plane | Owner | Content |
|------|--------|------|
| **Data** | **`invoke_service` / query tools** (platform) | Rows + column names + types; large results via the **cache / `fetch_cached_result`**, so full tables stay out of the prompt. |
| **Chart declaration** | **LLM** (strict JSON Schema) | Only **`kind`** (or `intent`), **column mapping** (`xColumn` / `yColumn` or multi-series), titles and axes, optional `yReferenceLines`. **No** business `y: [ ... ]` arrays. |
| **Merge and chart** | **Server (Java)** | Read table from a **named source** (`last_invoke` or `cache_id`) per declaration → type and column checks → caps → assemble `ChartBlock` → **wire** path. Invalid declaration → **structured error** for model retry. |

### 3.2 General chart tool

**`build_chart_from_tabular_result`** (see [`flexible-chart-solution.md`](../architecture/flexible-chart-solution.md)). A typical LLM turn runs **`invoke_service` (or another tabular tool)** first, reads the summary / column names / sample rows, **then** calls the chart tool with **column names and kind only**, not point values.

### 3.3 LLM guidance

- Tool **description + `llm_tool_routing_guide.txt`** state that dynamic table charting uses the chart tool and that coordinate arrays in the assistant body are not a formal chart.  
- Validation failures return explicit JSON codes for multi-turn correction.

### 3.4 Terminal

Terminal consumes only **valid `ChartBlock` JSON**, independent of whether it came from **`query_property_history`** or the **general chart build**.

### 3.5 Scope boundaries

The builder is declarative, verifiable and retryable — not an auto-BI analyzer: it does not pick a chart type without an explicit `kind` or `intent`, infer pivots / group-by / aggregates, draw dual axes, or overlay complex statistics. When aggregation is needed, a tool or service produces the target table first, then the chart is built from it.

---

## 4. Relationship to other docs

| Doc | Relationship |
|------|------|
| [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md) | Normative `ChartBlock` wire shape. |
| [`flexible-chart-solution.md`](../architecture/flexible-chart-solution.md) | Tabular chart build spec. |
| [`chart-intent.md`](../agent/chart-intent.md) | Intent mode, `chartId`, baseline chart paths. |
| [`prompt-to-chart.md`](../agent/prompt-to-chart.md) | Pie / grouped bar / §7.x rescue. |
| [`history-overlay-chart.md`](../agent/history-overlay-chart.md) | Overlay history chart tool. |
| [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) | `type: "chart"` delivery. |
| **`parler-agent/`** | **`BuiltInTools`**, **`ParlerChartWireSupport`**, chart executors. |
