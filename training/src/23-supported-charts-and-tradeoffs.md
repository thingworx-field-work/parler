# Supported charts: choosing an encoding and understanding its limits

**Agent 0.1.248 / Widget 0.1.97** supports seven chart kinds: **line, bar, scatter, pie, histogram, boxplot, and heatmap**. Horizontal and stacked bars, history overlays, and chart groups extend these kinds; they are not additional `kind` values.

The ordinary tabular build path has a **5000-input-row cap** for line, scatter, bar, pie and heatmap; this cap is not limited to line/scatter. Histogram and boxplot take a separate, already-summarized path with their own bin/group limits.

The [previous chapter](./22-prompt-to-response-and-chart.md) explains how model intent becomes a server-authored chart and which data can reach the LLM. This chapter answers a different question: which encoding should an application use, and what meaning or detail does that choice preserve or lose?

## 1. Choose by the question

| Question | Kind | Data preparation | Principal trade-off |
| --- | --- | --- | --- |
| How did a numeric property change over time? | `line` | Ordered time/numeric X and numeric Y | Connecting observations suggests continuity; an unobserved interval is not proof of a smooth process. |
| Which asset/state/group has the largest value? | `bar` | One value per category, optionally several series | Easy magnitude comparison; many categories or series become crowded. |
| Do two numeric measurements move together? | `scatter` | Paired numeric X/Y observations | Shows relationships without connecting them; does not prove correlation strength or causation. |
| What fraction of a whole belongs to each category? | `pie` | Unique category totals, nonnegative values, positive sum | Familiar composition view; close values and many small slices are hard to compare. |
| How are numeric observations distributed? | `histogram` | `tabulate_cached_result` with `bin_numeric` | Shows frequency/shape; bin boundaries change the apparent pattern. |
| How do distributions compare between groups? | `boxplot` | `tabulate_cached_result` with `box_summary` | Compact spread/median comparison; conceals distribution shape and individual values. |
| Where are high/low values across two categorical dimensions? | `heatmap` | Long table with one value per row/column pair | Compact matrix comparison; colour is less precise than position or a numeric table. |

For an exact audit list, a table may be more useful than any chart. Missing or incompatible data should trigger clarification, a supported transformation, or a clear fallback.

## 2. Line and scatter

Both kinds use series with paired `x` and `y` arrays. X must have a consistent date/time or numeric interpretation; Y must be finite numeric data. Multiple series share axes. The ordinary tabular builder accepts at most **5000 input rows** and **6 series**; exceeding its row limit is an error, not an automatic promise to downsample that table.

A **line** connects points in their supplied order. A **scatter** draws markers only. For example, plot temperature against time with line, or paired pressure against flow with scatter. Sort the source deliberately; connecting rows in an accidental order can mislead. Scatter does not itself calculate a regression line, correlation coefficient, significance or causal explanation.

Datetime axes use browser-local wall-clock labels. Preserve the source/request time zone in the question and explanation so a UTC query is not confused with the user's displayed local hour. For time series, `requestedTimeRange` can fix the X display domain; it is not a replacement for filtering the source query. Numeric X values do not become timestamps merely because the question contains the word “time.”

Multiple series share one value axis. Comparing incompatible units on that axis is misleading; a title cannot convert units. The history-overlay tool checks unit compatibility. There is no general dual-Y-axis option in this chart contract.

### History overlays

Use `build_history_overlay_chart` for **2–6** numeric property history traces, for the same named property, from the same or different Things/windows. It emits **line or scatter**, with three X modes:

| Mode | Meaning | Useful for | Information that becomes less direct |
| --- | --- | --- | --- |
| `absolute_time` | Original timestamps | Simultaneous events across assets | Shifted periods appear in different positions. |
| `elapsed_time` | Seconds since each window began | Comparing startup/run progression | Calendar-time alignment is no longer on the X axis. |
| `normalized_time` | Fraction of each requested window, 0–100% | Comparing windows of different durations | Equal X fractions represent different elapsed durations. |

These are the tool argument values for `xAxisMode`; wire values for the transformed modes are `elapsed` and `normalized`. If omitted, the tool selects `absolute_time` when windows match and `elapsed_time` otherwise. Request `normalized_time` explicitly. The executor also accepts `absolute`, `elapsed` and `normalized` as input aliases, but the advertised tool-schema enum uses the three `*_time` values.

Normalized X is a display alignment, not proof that the processes had equal speeds, and not a resampling/interpolation operation. The tool reads at most **5000 history rows per series** and emits at most **5000 points across the chart**. If necessary it samples for presentation and reports truncation. A read-limit warning concerns potentially missing source history; presentation sampling concerns the points drawn. They are different limitations. A sampled chart is unsuitable for proving every short excursion or computing exact statistics from the visible markers alone.

## 3. Bars: categories, orientation, grouping and stacking

Use a bar chart for the three state totals in the walkthrough: Running 300, Idle 120, Fault 60. Position and length make these magnitudes easy to compare.

The tabular builder supports at most **24 categories** and **6 series**. It can read wide data (`series` with several Y columns) or long data (`seriesColumn` separates groups, `yColumn` contains values). A long table must have at most one value for each category/series pair; aggregate duplicates with `group_metric` first. The grouped-bar pivot fills missing combinations with zero and reports that transformation. Use it only when zero is an appropriate representation of absence; missing observations and zero activity are not interchangeable business facts.

`orientation: "horizontal"` is particularly useful for long category labels. Tool guidance tells the model to use it only when the user explicitly asks; the server validates the supplied option and chart kind, not whether the user's prose contained that request. It changes layout, not grouping or aggregation. Vertical remains the default; a horizontal plot can become tall and scroll within the card.

| `stackMode` | What the user sees | Why choose it | Trade-off |
| --- | --- | --- | --- |
| Omitted / `grouped` | Adjacent series bars per category | Compare components directly | Total magnitude is less immediate. |
| `stacked` | Components accumulated per category; positive and negative separately | Compare additive totals and composition together | Interior segments lack a common baseline. Summing rates or incompatible units would be meaningless. |
| `percent` | Each component's share of the category total, 0–100% | Compare composition across unequal totals | Absolute size differences are hidden. Negative values are rejected. A zero-total category has no bar. |

Stacked/percent modes require at least **two series**. Tool guidance asks the model to use them only on an explicit user request; the server validates the mode, bar kind, series count and values, without checking the wording of the prompt. Intent selection never chooses stacking on its own; a stack needs a supplied `stackMode`. They can combine with horizontal orientation. The wire preserves source values; the client computes percent shares for presentation. Hiding a series does not change the original percent denominator into “the visible series total.” This preserves the original meaning, but users expecting automatic renormalization must understand the difference.

## 4. Pie

A pie accepts exactly **one series**, nonnegative values, and a positive total. The builder excludes zero-valued slices and requires unique nonzero labels. It does not aggregate duplicate labels on the model's behalf. “Running” in many source rows must first become one Running total.

Two policies are available:

- `pieSliceMode: "top_with_other"` (default): when nonzero categories exceed `pieMaxSlices`, keep the largest `maxSlices − 1` and sum the remainder into **Other**.
- `pieSliceMode: "all_nonzero"`: retain all nonzero categories if they fit; otherwise return an error instead of silently dropping slices.

`pieMaxSlices` defaults to **8**, with an allowed range of **2–12**. Other preserves the total while hiding the individual contribution of small categories. Use the source table or a separate detail view when those categories matter. A bar chart is usually clearer for close values, ranking, or many groups.

Request `kind: "pie"` explicitly. The intent `composition` currently selects a **bar**, not a pie; “composition is supported” does not mean the intent resolver automatically chooses the circular encoding.

## 5. Histogram

First run `tabulate_cached_result` with `mode: "bin_numeric"`, a numeric `column`, and exactly one of:

- `binCount`: **1–50** equal-width bins, optionally bounded by `rangeMin`/`rangeMax`;
- `binEdges`: **2–51** strictly increasing finite edges for explicit, possibly unequal widths.

Then call `build_chart_from_tabular_result` on the returned derived `cacheId` with `kind: "histogram"` and no `xColumn`, `yColumn`, `series`, or `seriesColumn`. The source already contains the bins; chart construction validates rather than invents/recomputes them.

For example, the chart step after a successful bin operation is:

```json
{
  "source": "cache_id",
  "cacheId": "<bin-result-cache-id>",
  "kind": "histogram",
  "histogramMode": "density",
  "title": "Distribution of cycle duration"
}
```

`histogramMode: "count"` is the default. `density` divides bin count by the in-range count and bin width, so area represents probability mass; it is useful for unequal-width bins. Count height with unequal widths does not have that area interpretation.

Bins include the left edge and exclude the right edge, except that the last bin includes its right edge. Uniformly numeric-parseable STRING columns are also supported: numeric-looking strings are included, while nulls, values that cannot be parsed as numbers and nonfinite values are excluded and counted. Values outside specified edges are reported separately. Fewer/wider bins give a smoother overview but can hide modes; more/narrower bins reveal detail but can amplify small-sample noise. The chart is not a fitted probability distribution. The client offers neither re-binning nor a count/density toggle; request the desired mode when building it.

## 6. Boxplot

First run `tabulate_cached_result` with `mode: "box_summary"`, a numeric `column` and optional single-column `groupBy`. Build `kind: "boxplot"` from that derived cache, without ordinary X/Y bindings.

The server computes linear-interpolated quartiles, median, minimum, maximum, and Tukey **1.5 × IQR** whiskers. Whiskers end at observed values within the fences; they are not always the min/max. Values beyond them are reported as outliers. A box can contain one valid observation, so inspect `n` before treating it as a substantial sample.

There are at most **24 distinct source groups** and at most **20 displayed outliers per group**. The 25th distinct group triggers `TOO_MANY_GROUPS`, including groups that would later be dropped for having no valid numeric values. All outliers are counted; only a bounded subset is plotted. This keeps the card readable, but the visible dots are not a complete outlier list. The rendering is vertical only, without notches or a mean marker. Two very different distributions can share the same quartiles; use a histogram or source details when multimodality matters.

An outlier is a statistical classification under this rule, not automatically a sensor fault or a specification violation. Specification/control limits, if available, are separate reference lines.

## 7. Heatmap

Use a long table with two categorical dimensions and one numeric measure. For a machine-by-shift summary:

- `seriesColumn`: machine (rows);
- `xColumn`: shift (columns);
- `yColumn`: the metric, such as minutes.

`group_metric` with two grouping keys can prepare one row per pair. The chart builder pivots the result into at most **24 rows × 48 columns**. Duplicate pairs need aggregation first. Missing pairs stay `null` and appear as hatched **No data** cells; they are not zero-filled as grouped bars are. An all-missing matrix cannot produce a valid chart.

Colour follows the current Theme: sequential for values on one side of zero, diverging symmetrically around zero when signs mix. Missing cells are distinct from numeric zero. There is no custom scale picker, correlation-matrix computation or client-side row/column sorting. Wide/tall matrices scroll on screen and compress for print.

This is an explicit matrix encoding, not automatic evidence that the two dimensions are correlated. Colour emphasizes patterns but makes close numerical comparisons harder; use cell readouts or the data view for exact values.

## 8. Reference lines and chart groups

Up to **12 Y reference lines** can be requested on line, bar, scatter and boxplot. They can represent target, warning, specification or control limits. Histogram and heatmap reject them; pie does not use a value-axis reference line. Values must come from an identified specification, tool result, or explicit user input. Adding a `ucl` label does not calculate a statistically justified control limit or turn a line plot into a complete SPC analysis.

`declare_chart_group` organizes **2–6 chart slots** into one related presentation, with at most **one group per request**. Each chart is built separately and bound using `groupMemberKey`; member states include pending, ready, no-data, error and cancelled. Optional `sharedCategoryDimension` enables consistent colours for matching category labels where supported, with a bounded set of **24 shared category keys**. It does not combine data, synchronize units or establish common axes.

The benefit is a coherent comparison with partial failures visible. The cost is additional tool calls, layout space, and the need to label related charts clearly. A chart group is neither a new chart kind nor an unrestricted dashboard authoring system.

## 9. Explicit kinds versus intent selection

`build_chart_from_tabular_result` accepts **either** `kind` **or** `intent`, never both. Kind requests a specific supported encoding; intent asks the server to choose through fixed rules.

| Intent | Current selection / boundary |
| --- | --- |
| `time_trend` | Line when X has a supported numeric/time shape. |
| `rank` | Bar, with descending ranking behavior. |
| `compare_groups` | Bar for supported input shapes; an unsupported grouping shape can fall back. |
| `correlation` | Scatter with numeric X; it does not compute a correlation statistic. |
| `composition` | Bar with ranking behavior. Pie requires explicit `kind`. |
| `distribution` | Histogram for a `bin_numeric` source, boxplot for a `box_summary` source; raw observations must be summarized first. |
| `status_timeline` | Returns `CHART_FALLBACK` with `status: "success"` before column validation; no chart is emitted. A chart-group member classifies this fallback as no-data. |

Intent mode also has shape checks, including a general two-row guard outside the distribution path. A valid explicit chart and an automatically chosen chart need not have identical admission rules. A fallback is not a successful chart emission; the model should explain it or choose a supported alternative from valid evidence.

There are no current kinds for area/stacked area, donut, radar, bubble, candlestick, Sankey, treemap, gauge, map, 3D, or Gantt/state timeline. A prompt or skill cannot add a renderer. In particular, totals by state can be a bar or pie, but that loses the chronology and transition order of a state timeline.

## 10. Why maintain a bounded chart vocabulary?

The same validated chart data supports a consistent widget, inspectable values and history rendering. Fixed statistical preparations keep bins/quartiles reproducible; explicit caps keep payloads and layouts manageable. The model needs to select data and intent instead of generating a large rendering program.

This gives up the freedom of arbitrary model-authored graphics. New kinds require aligned server construction, wire validation, UI rendering, tests and documentation. Existing kinds still require sound choices of units, filters, grouping and time windows. Server validation can reject malformed numbers; it cannot determine that “sum of utilization percentages across unrelated machines” was the business question the user should have asked.

Chart-generation independence is also separate from data privacy: small `chartBlock` data can reach the LLM through tool evidence, as the [data-flow chapter](./22-prompt-to-response-and-chart.md) explains. The advantage is deterministic construction from source results, with explicit boundaries and trade-offs, rather than a claim that no business data leaves ThingWorx.
