# Chart enhancement — chart cards, chart kinds and chart groups

This document describes how Parler charts work inside the Composer widget: the chart card and its
interactions, numeric and time correctness, input validation, the chart kinds and the statistics operators
behind them, multi-chart layout and chart groups, history, print and performance.

The delivery surface is the Lit `<parler-ui>` component in `parler-ui-widget`, talking to `parler-agent` on
ThingWorx over AlwaysOn and platform services. Charts are structured data built on the server, validated on
the client and drawn with D3. The model never produces HTML, JavaScript or plot coordinates.

Normative wire rules are in [`CONTRACTS/CHART_CONTRACT.md`](../../../CONTRACTS/CHART_CONTRACT.md) (ChartBlock,
kinds, limits, `chart_group`), [`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../../CONTRACTS/UI_CLIENT_PROTOCOL.md)
(wire → UI events and rows), [`CONTRACTS/API_CONTRACT.md`](../../../CONTRACTS/API_CONTRACT.md) (frames and
execution phases) and [`CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md) (the
distribution operators). Styling hooks (parts, tokens, fixed behavior) are in
[theme-api.md](../../ui/theme-api.md). Section numbers and the labels C0, V1, C1a, C1b, L1, C2a, C2b, D1,
C3a, C3b-1 and C3b-2a are stable: code comments and tests cite them.

## 1. Product goals and flow

A user in a Mashup asks about equipment utilization, temperature or energy trends, alarm composition,
quality distributions and anomalies, and should be able to read a chart, look up values, compare, pick a
range and print within one answer, without leaving the widget. Correct coordinates and values, readability
in narrow containers, consistency between chart and statistic, and faithful replay matter more than the
number of chart kinds.

Supported scenarios:

1. Single- or multi-device trends with full time, units, thresholds and zoom.
2. Device ranking and status composition, with readable long names, per-device pies, grouped, horizontal
   and stacked bars, and consistent colours for the same category across a chart group.
3. Distribution and stability: histograms, box plots and device × period heatmaps computed by
   deterministic server-side operators.
4. Several related charts in one answer, laid out side by side or as a declared chart group.

KPIs belong to result panels and thresholds to chart layers (reference lines); not every presentation is a
separate chart kind.

## 2. Components and limits

| Area | Behavior |
| --- | --- |
| Kinds and building | `line`, `bar`, `scatter`, `pie`, `histogram`, `boxplot`, `heatmap`. `line` and `scatter` support multiple series; `bar` supports wide and long (`seriesColumn`) tables, horizontal orientation and stacking. There is no separate `grouped_bar` kind. Built by [`ParlerTabularChartBuilder`](../../../parler-agent/src/main/java/com/thingworx/things/agent/ParlerTabularChartBuilder.java). |
| Intent routing | `kind` and `intent` are mutually exclusive. `time_trend` → line; `rank` and `composition` → sorted bar; `compare_groups` by shape; `correlation` needs a numeric X; `distribution` by source shape (§7.4); `status_timeline` returns a structured fallback. Pie and heatmap only by explicit `kind`. [`ParlerTabularChartIntentResolver`](../../../parler-agent/src/main/java/com/thingworx/things/agent/ParlerTabularChartIntentResolver.java). |
| Several charts per turn | [`AgentToolContext`](../../../parler-agent/src/main/java/com/thingworx/things/agent/tools/AgentToolContext.java) queues pending charts; the emitted count only grows after a successful downlink. Tool execution concurrency is unchanged. |
| Presentation budget | [`AgentLoop`](../../../parler-agent/src/main/java/com/thingworx/things/agent/AgentLoop.java) `PRESENTATION_ACTION_LIMIT = 6` limits presentation-phase dispatch batches; it is not a global chart count. The one-shot chart rescue fires when chart building was attempted and nothing was emitted, under its own conditions. |
| Time and reference lines | Datetime X uses a true time scale; request windows, elapsed seconds, normalized fractions, Y reference lines and history overlays are supported. Line and scatter never fill zeros, but a line still joins neighbouring points, so a gap is not proof of normal operation. See [history-overlay-chart.md](../history-overlay-chart.md). |
| Charts and tables | `row.artifacts` keeps the live cross-type order. [`artifactPresentation`](../../../parler-ui/lib/artifactPresentation.js) pairs a chart with its direct parent table only when `chart.source.sourceCacheId === table.cacheId`, and shows each table once. Tables in rows with charts start collapsed; rows without charts expand at most two tables. Table preview is 5 rows. |
| Style and output | [`parler-ui-chart`](../../../parler-ui/components/parler-ui-chart.js) uses light DOM and follows size and Theme; answer print renders chart cards with the print palette ([assistant-response-actions.md](../../ui/assistant-response-actions.md)). There is no per-chart SVG or PNG download. |
| History | [`historyHydrate`](../../../parler-ui/lib/historyHydrate.js) validates charts, tables and groups; rows without `artifacts` are rebuilt from the legacy buckets. Transient view state is not persisted. |
| Widget interface | ThemeMode and CustomClass are bridged. There is no external UserPrompt property or Mashup selection event; interactions use the widget's own submit path. See [parler-ui-widget/README.md](../../../parler-ui-widget/README.md). |

Limits: tabular chart building reads at most 5,000 rows; grouped bars at most 6 series and 24 categories;
pie at most 8 non-zero slices by default and 12 with `all_nonzero`; histogram at most 50 bins; box plot at
most 24 groups and 20 shown outliers per group; heatmap at most 24 rows × 48 columns. History overlays
allow at most 6 series and 5,000 emitted points in total, split per series and uniformly down-sampled when
exceeded; each history read has its own row limit. Fetched counts are not the size of the underlying
business population, and the UI never hides these limits.

## 3. Responsibilities and protocol boundaries

Data path: ThingWorx service or history → cache or deterministic transform → server chart builder →
AlwaysOn `chart` frame → wire adapter → answer artifacts → card and D3 → history and print.

- **Agent and computing tools** own source permissions, window resolution, filtering, sorting,
  aggregation, binning, quantiles and method versions. The model picks admitted tools and parameters.
- **Chart builder and resolver** own shape validation, explicit column binding, kind decisions, source
  notes, chart limits and explainable fallbacks. The renderer never re-aggregates input to "repair" it.
- **UI adapter** owns validation, artifact identity, display projection and transient view state. An
  invalid chart never affects the valid charts, tables or text of the same answer.
- **Component and renderer** compute geometry, hit testing and accessible text from the validated chart,
  layout, Theme and view state only. They never query the platform or call the LLM.
- **Message persistence and history export** own replayable results. The screen DOM is never the source
  of history or print.

A change to a kind, field, event, history schema or parser acceptance touches producer, adapter, JSDoc
types, renderer, history and print together, and the normative contract change is committed with a
`CONTRACT_VERSION` bump.

## 4. Single chart card and in-widget interaction

### 4.1 Card structure and data lookup

Card structure is C1a-1; point query and time ticks are C1a-2; legend interaction, view state, View data
and data notes are C1a-3 (C1a-3a inside the card, C1a-3b host integration).

Each card has, in order: title; meta line (window, unit, known limits); plot area; legend; action bar; a
collapsed "data notes" section. Title, legend and buttons are DOM outside the SVG and never cover data.
Without a structured unit the existing axis label is shown; no unit is invented. A missing limit is shown
as "not provided by the source", never as complete. All text is rendered as text.

The action bar shows only delivered actions: **View data**, **Expand**/**Close**, **Reset view**, **Select
visible range**/**Clear selection** (line and scatter), and the legend toggles.

**View data** dispatches a cancelable `chart-view-data` event. The host locates the direct parent table
(`parentTableArtifactForChart`), expands it and moves focus to it; charts sharing one parent table resolve
to the same table, which stays once in the DOM. Without a parent table the card opens its own paged
"chart data" view (`section.chart-data`, 20 rows per page, `chartDataRows` / `chartDataPage`) listing the
points or aggregates the chart received, and states that these are not all raw records. Focus returns to
the button on close. The data view is not printed.

The **data notes** ([`chartDataNotes`](../../../parler-ui/lib/chartDataNotes.js)) show what the UI already
has: source name, query and analysis windows (in the display time zone, with both offsets when they differ,
and `sourceWindow.resolvedTimeZone` as a separate "source window zone"), column mapping, transform, input
rows, emitted points, Top-N / Other, zero-filled combinations, sampling and `missingPeriods`. The `cacheId`
only appears in diagnostics. Fetching more records is not a card action.

### 4.2 Responsive layout and accessibility

Layout follows the card's measured content width, not the browser viewport. Plot text defaults to
12 CSS px for ticks and legend (Theme range 6–18 px; titles 8–16 px); customer overrides go through the
[Theme API](../../ui/theme-api.md) and the SVG is never scaled to compensate. A card measured at zero size
draws later, when its ResizeObserver sees it.

Titles wrap; the legend wraps; long category labels may be shortened on the axis, but the full name is
always available in the tooltip, keyboard query and View data. Series are distinguished by text and mark
shape as well as colour. Toolbar buttons have accessible names and states.

**Point query.** The plot area (`div.chart-plot-area`, `tabindex=0`, `role=group`) is one keyboard stop.
Arrow keys move between neighbouring points or series (left/right = neighbouring categories or X, up/down
= neighbouring series and reference lines), Home/End go to the ends, Escape closes the tooltip and keeps
focus. Pointer hover uses the same hit model. The tooltip (`part=chart-tooltip`, `role=status`) gives the
full value, series, unit, time and display time zone; reference lines can be queried too. Hover and focus
are transient component state, cleared when the chart snapshot changes and restored by identity after a
redraw.

### 4.3 Legend, zoom, selection and expand

| Action | Behavior |
| --- | --- |
| Line / bar / scatter legend | Toggles series (`button.chart-legend-toggle[aria-pressed]`). The Y domain stays that of all series plus reference lines unless the user picks "fit visible series"; the view note states the mode. With all series hidden the axes stay with a "no series selected" note and a restore action. Hidden series keep their colour slot. |
| Pie legend | Focuses and highlights a slice. Percentages and denominators are never renormalized. |
| X zoom (line, scatter) | Changes only the view X domain, recomputes ticks and clips the data layer; the original domain is a hard boundary. Bar, pie and the distribution kinds have no zoom. |
| Selection | Stores the current view range as a labelled band that can be cleared. It sends no request. |
| Reset view | Restores the full X domain, all series, the default Y policy, and clears the selection and focus. No data is refetched. |
| Expand | Shows one large chart inside the widget, keeping card state and chat scroll position. |

Several widget instances on one page each own their overlays, keyboard handling and tooltips.

### 4.4 State and rendering

[`chartViewState`](../../../parler-ui/lib/chartViewState.js) holds `viewXDomain`, `selectedRange`,
`hiddenSeriesKeys`, `yDomainPolicy`, slice focus and the notes-open flag. These are UI-internal and never
enter a ChartBlock. The widget owns the state, keyed by `chartViewStateKey(conversationId, requestId,
artifactKey)` using the chartId/source/seq rules of `artifactPresentation`. Series keys are the original
series index within the chart snapshot. `chartSnapshotSignature` (kind plus a hash of every series' name,
length and x/y values, including the difference between null and 0) detects a new snapshot: series
visibility is reset, zoom and selection are clamped to the new domain or cleared. Clearing the conversation
clears the state.

`drawChart(el, chart, theme, view, options)` in
[`chart-draw.js`](../../../parler-ui/components/chart-draw.js) receives the chart, Theme, layout and a
read-only view and returns the geometry needed for hit testing
([`chartHitModel`](../../../parler-ui/lib/chartHitModel.js)). The component turns user actions into state
updates and redraws; no state lives in SVG nodes. Resize and Theme changes keep the view, legend choices
and focus. Disconnecting a component removes its observers and listeners.

Theme handling uses [`chart-theme.js`](../../../parler-ui/components/chart-theme.js),
`parler-ui/lib/themeTokens.mjs` and `parler-ui/styles/parler-ui.css`; new parts and fixed behaviors are
listed in theme-api.md.

### 4.5 Full-view print, X zoom and selection, inline expand

**C1b-1 — full-view print output.** Print membership comes from the answer's ordered artifacts
(`orderArtifactsForDisplay(row.artifacts)`), never from the screen DOM. For each chart artifact the pure
function `renderChartPrintCard({ chart, theme, width, viewSummary, doc, colorKeys })` builds a detached
`figure.chart-card` with title, meta, the plot redrawn in the **default view** (full X domain, all series,
`full` Y policy, no focus), a full legend, expanded data notes and a print note. The Theme is resolved once
by `resolveChartTheme(host)`; the width is the measured width of the chart's card (`figure.chart-card`),
else the answer bubble, else 640. When the screen view differs from the default, the card carries
`p.chart-print-note` ("Printed with the full analysis range and all series; on screen: …", including zoom
and selection). `assembleChartCardsForPrint(clone, artifacts, buildCard)` in
[`assistantResponseActions`](../../../parler-ui/lib/assistantResponseActions.js) replaces each cloned
`parler-ui-chart[data-parler-artifact-key]` with its built card and inserts missing members (expanded, not
yet mounted or removed) in artifact order, so the printed cards always match the chart artifacts in number
and order. It runs after `stripNonPrintableControls` and before `rewriteClonedChartPaletteForPrint`. The
screen view note, action bar and data view are not printed.

**C1b-2 — X zoom, selection and reset (line and scatter only).**

- State values are numbers on the X axis: epoch milliseconds (absolute), seconds (elapsed), fractions
  (normalized) or raw values (numeric). The original domain, including `requested_time_range`,
  `elapsedDomain` and `normalizedDomain`, is a hard limit.
  [`chartXDomain`](../../../parler-ui/lib/chartXDomain.js) provides `chartXDomainInfo`, `clampXRange`
  (minimum span 1/1000 of the full domain, shared by sliders, drag and reconcile), `clampXSelection` (a
  full-domain selection is kept and differs from "no zoom") and the formatters.
- Control: `div.chart-range[part=chart-range]` under the plot, two native `input[type=range]` ("View
  start", "View end", 0–1000 over the full domain, `aria-valuetext` formatted). The start cannot pass the
  end. A horizontal drag of at least 8 logical px in the plot is an optional zoom gesture; wheel and
  vertical touch scrolling stay with the chat.
- Rendering: only the X scale domain changes; datetime ticks use `fitTimeTicks`; the data layer is clipped;
  the Y domain does not follow the X view; the hit model contains only points in view.
- Selection: "Select visible range" stores the view as `selectedRange`, drawn as `rect.chart-selection`
  (palette role `grid`), with start/end labels and "Clear selection".
- `describeChartView` adds "Zoomed to a – b of c – d" and "Selected: a – b".

**C1b-3 — inline expand.** The widget holds `#expandedChart = { requestId, artifactKey, scrollTop }`
(not part of the view state; cleared with the conversation, on disconnect, and when the artifact leaves the
answer). The host renders `div.chart-expand-layer[part=chart-expand]` inside `.thread-wrap` with
`role=dialog`, `aria-modal` and the chart title as label; it never takes over `document.body`. The layer
renders the **only** `parler-ui-chart` instance of that artifact, sharing the host-owned view state, while
the answer shows `div.chart-expand-placeholder` ("Chart expanded", `data-no-print`, focusable). Thread,
header, composer and jump-to-latest are `inert` and `aria-hidden`; Tab cycles through the layer's
currently reachable controls ([`keyboardReachable`](../../../parler-ui/lib/keyboardReachable.js)); Escape,
the close button or the card's Close restore the thread scroll position and return focus to the card's
Expand control, else the answer, else the thread. View data while expanded first closes the layer. Removing
the answer or artifact removes the layer without leaving elements behind. Print is unaffected (C1b-1).

Parts: `chart-print-note`, `chart-range`, `chart-selection`, `chart-expand`, `chart-expand-close`,
`chart-expand-placeholder`.

### 4.6 L1: plot size by chart kind

The plot size is a pure function of `(kind, data counts, available width W)`:
`chartPlotSize({ kind, orientation, categories, seriesCount, marginLeft, marginRight, availableWidth,
context, availableHeight })` in [`chartSizePolicy`](../../../parler-ui/lib/chartSizePolicy.js) returns
`{ w, h, legendPlacement, legendWidth, availableWidth }` (and `cell` for heatmaps), with
`context ∈ "card" | "expand" | "print"`. Model output, tool parameters and wire fields never influence
layout. Card, expand layer and print call the same function; the same full input always gives the same
result. 1 logical px = 1 CSS px; when `w < W` the plot is centred, not stretched.

| Class | Kinds | Plot size |
| --- | --- | --- |
| Full width | line; scatter (all X modes); histogram | `w = W` (minimum 240), `h = 240`. |
| Intrinsic | pie | Square side `clamp(round(W / φ), 200, 400)` with φ = 1.618; radius `side / 2 − 8`. At W = 320 / 480 / 768 / 1200 the side is 200 / 297 / 400 / 400. |
| Band | vertical bar; boxplot | Step cap `maxStep = min(96 + 32 × (S − 1), 256)` with S series slots (hidden series keep their slot; boxplot uses 1). `w = clamp(marginLeft + n × maxStep + marginRight, 240, W)`; plot height 240 plus the measured label overhang below. |
| Height budget | horizontal bar | §7.3. |
| Cells | heatmap | §7.4. |

**Pie legend placement.** When `W − side − 24 ≥ 240` (W ≥ 664) the legend sits beside the disc
(`legendPlacement = "aside"`, gap 24, column width `min(320, W − side − 24)`) and each row shows label, value
and percentage from the same source as the tooltip (`pieLegendValues`); otherwise it stays below. In the
expand layer the pie cap is the layer's inner height; print uses the print width and the 400 cap.

**Rotated category labels.** Vertical bar and boxplot category labels are rotated −35° and anchored at the
tick. `layoutRotatedCategoryAxis` measures them: the bottom margin is
`clamp(tick + overhang + 6 + axis-title line + 8, 44, 160)`, the extra height is added below the plot, and
the left margin grows when the first labels would overhang the left edge (capped at `0.4 × W`). Labels that
still do not fit are shortened by **measured pixel width**. When the band step is below 1.75 × the tick
font size, only every k-th label is drawn (first and last always); bars, boxes and hit targets are all kept.
The label list is the set of bands actually drawn.

**Numeric axis ticks.** Histogram X ticks use `fitLinearAxisLabels`: starting from the responsive tick
count and decreasing to 1, each candidate is checked against the final drawn geometry (the scale's real
tick positions, measured label widths, inward anchoring of edge labels), requiring every label inside the
SVG with at least 8 px between neighbours; if none fits, no labels are drawn. Line and scatter numeric,
elapsed and normalized X axes choose the tick count from the width only.

**Margin order.** The left margin comes from the Y tick labels, and the tick count depends on width, so
the order is a single pass: estimate margins with the tick count for `W`, compute `w`, draw with the tick
count for `w`, and never feed the drawn margins back into `w`.

**Measurement source.** The component observes `figure.chart-card` and uses its content width as `W`;
only a change of `W` triggers a resize redraw. It writes `w` as the explicit width of `.chart-root`
(`data-plot-width`, centred by CSS) and sets `figure[data-legend-placement]` and
`--parler-chart-legend-width`. Tooltip, hit and reveal coordinates are relative to the plot and include the
root offset. The print width is the card's `W`.

Fixtures (`lib/chartSizePolicy.test.mjs`, `lib/chartDrawBar.test.mjs`, `lib/chartCard.test.mjs`,
`lib/parlerUiChartPrint.test.mjs`):

| Id | Assertion |
| --- | --- |
| LS-1 | Pie side 200 / 297 / 400 / 400 at the four widths; square viewBox; radius `side/2 − 8`; centred. |
| LS-2 | Legend below at 320 and 480, aside at 768 and 1200; aside rows equal the tooltip text; focusing a slice highlights its row without recomputing percentages. |
| LS-3 | Vertical bar, `n` = 2 / 6 / 24: `w` follows the formula with the renderer's real margins (2 categories: 244 at both 320 and 1200, centred); 24 categories fill the width; `S = 6` gives `maxStep = 256`. |
| LS-4 | Line, time and numeric scatter, elapsed/normalized and horizontal bar geometry is unchanged at the four widths. |
| LS-5 | Card, expand layer and print all use `chartPlotSize`; print width is the card's `W`. |
| LS-6 | Root width equals `w` and is centred; pointer, tooltip and keyboard reveal land on the mark; repeated resizes return the same size; a height-only change does not redraw. |

## 5. Numeric and coordinate correctness

### 5.1 C0: signed bars

For the finite values `V` of all drawn bars and Y reference lines, the raw Y domain is
`[min(0, min(V)), max(0, max(V))]`, then d3 `scaleLinear().nice()` with `responsiveChartTickCount`. All
zeros with no non-zero reference line use `[-1, 1]`. One computation feeds margin, grid, reference lines
and bars (`chartYDomain(values, referenceYs, kind)`, `signedBarYDomain`); line and scatter keep their
"data ∪ reference lines" extent. `responsiveChartLeftMargin(yDomain, kind, tickCount, theme)` uses the same
domain.

For a value `v` with `y0 = yScale(0)`, a bar has `y = min(yScale(v), y0)` and
`height = abs(yScale(v) − y0)` (`barRectGeometry`). Mixed, all-positive and all-negative data use the same
rule. A zero bar keeps a zero-height rect so it can still be queried. A zero baseline is drawn at
`yScale(0)` only when the domain goes below zero, with the `axis` palette role and line width; all
non-negative bars have only the category axis at the bottom. Grouped bars compute the domain over the
shared category count of their series. Print uses the same geometry.

Regression data: `[-5, 10]`, `[-5, -10]`, `[0, 0]`, multi-series mixed signs, and positive data with a
negative reference line (`lib/chartDrawBar.test.mjs`).

### 5.2 Time axes

Datetime X keeps true proportions: `00:00 / 00:01 / 01:00` are spaced 1 : 59. Absolute, elapsed (seconds)
and normalized (`[0, 1]`, relative progress) modes never substitute for each other.

[`chartTimeFormat`](../../../parler-ui/lib/chartTimeFormat.js): tick labels are HH:mm within one day,
MM-DD HH:mm across days and MM-DD when every tick is at midnight, with seconds or milliseconds added only
when needed to tell ticks apart, and a UTC offset only when the same local time occurs under different
offsets. `fitTimeTicks` reduces the tick count by measured label width and drops ticks that would leave the
plot; if all candidates overflow it picks the most central tick that fits, and otherwise draws no tick
label rather than a clipped one. Tooltips show the full local time with milliseconds and the display time
zone (browser local time); a source window's `resolvedTimeZone` is shown separately. Normalized tooltips
keep the raw fraction and add a percentage.

Server builders emit finite numbers only: `ParlerTabularChartBuilder.cellToNumericY` parses BOOLEAN and
numeric strings on the server, rejects null with `ROW_ALIGN_FAILED` and other non-finite values with
`Y_COLUMN_NOT_NUMERIC`; `HistoryOverlayChartBuilder` and the `points[]` path of `ParlerChartWireSupport`
drop invalid charts. Duplicate X, unsorted input and empty windows follow the producer's own rules; the
renderer never averages to hide shape errors.

### 5.3 V1: client input validation

Two history-export paths replay a persisted `chartBlock` as-is without checking Y:
`chartBlockFromChartEmittedToolResult` (`CHART_EMITTED` results) and
`chartBlockFromNumericCompactToolResult` (`NUMERIC_HISTORY_INLINE` / `NUMERIC_HISTORY_AGGREGATES`
envelopes); `AgentMessageStreamHistoryExporter.chartsFromToolRows` takes the first match in the order
chart-emitted → compact → points. The client adapter (`historyHydrate → asChartBlock`) is therefore the
only validation layer for such history, and the same adapter serves the live wire.

**Legal Y.** For every kind and X mode, each `series[].y[i]` must be a JSON number and finite
(`typeof y === "number" && Number.isFinite(y)`). Null, missing or sparse slots, booleans, any string
(including `"5"`), NaN and Infinity are illegal. Numeric zero stays zero. BOOLEAN → 0/1 and string parsing
are producer rules; the client performs no coercion (CHART_CONTRACT §3.2, §3.3 invariant 3).

**Shape.** Every series needs `Array.isArray(x) && Array.isArray(y) && x.length === y.length &&
x.length > 0`; one bad series rejects the whole chart. X value rules are unchanged.

**Invalid chart.** The adapter drops the frame and emits no `assistant.chart` (CHART_CONTRACT §4.2); other
charts, tables and text of the answer are unaffected. A `console.warn` records chartId, kind and a
diagnostic code: `CHART_KIND_INVALID`, `CHART_SERIES_EMPTY`, `CHART_SERIES_SHAPE`,
`CHART_Y_NOT_FINITE_NUMBER`, `CHART_PIE_SERIES_COUNT`, `CHART_PIE_NEGATIVE`, `CHART_PIE_ZERO_TOTAL`,
`CHART_FIXED_DOMAIN_INVALID`, plus the per-kind codes of §7.3 and §7.4. The codes are diagnostics, not wire
fields. The renderer keeps a defence in depth: `drawableSeries(chart)` reuses `chartSeriesRejection` and
draws nothing if any series is invalid; it never draws a partial chart or filters series.

Implementation: `parler-ui/lib/wireAdapter.js` exports `chartSeriesRejection(series)`,
`rejectChart(code, raw)` (identity fields only print strings or finite numbers, never throw) and
`CHART_REJECTION_CODES`; `asChartBlock` runs the shared check, then the per-kind rules, and does not
convert Y. Tests: `lib/chartWire.test.mjs`, `lib/historyHydrate.charts.test.mjs`,
`lib/chartDrawInputDefense.test.mjs`, `lib/chatSessionArtifacts.test.mjs`.

## 6. Data semantics and traceability

### 6.1 Four layers of counts and two ranges

| Layer | Facts to express |
| --- | --- |
| Query / source coverage | Requested window, rows fetched, whether the read reached its limit, known or unknown coverage; an unknown total stays unknown. |
| Cache / statistic input | Input snapshot or derived-result identity, included/excluded counts, method and parameters, analysis window, completeness limits. Reading a whole cache does not prove the business source complete. |
| Emitted data | Points or rows the builder sent, series count, Top-N / Other, synthesized zeros and down-sampling, separately from input counts. |
| Drawn data | What the UI draws. It is never the input of a histogram, KPI, quantile or exceedance statistic. |

The **analysis range** belongs to the result; the **view range** is what the user looks at now. Zooming a
week-long line to one hour does not turn a neighbouring weekly histogram into an hourly statistic.

A derived cache's `sourceCacheId` points to its real input; the UI only pairs charts and tables by these
explicit relations, never by cache names or titles.

### 6.2 Missing values, units and statistical methods

- Zero, unknown, excluded and unobserved are handled separately. Grouped bars keep their missing-combination
  zero fill and disclose it with `filledMissingCombinations`; heatmaps show missing cells as missing.
- Finite Y values in line and scatter do not encode gaps.
- Units are not inferred from label text; different quantities stay on separate charts and there is no
  dual Y axis.
- Bin edges, quantile definitions, whisker and outlier rules, count denominators and density normalization
  are deterministic server computations (§7.4); the chart is a view of their result table. Quantiles use the
  same `LinearPercentile` implementation as `group_metric` `percentile`.
- Interval, state and event semantics (`[start, end)`, unknown coverage, hold rules) are those of the
  computing operators in [computing-enhancement.md](computing-enhancement.md) §3.

## 7. Chart kinds and intent

### 7.1 Kind overview

Each kind has its own payload structure; special fields are never packed into the generic `series[].x/y`
for the renderer to reinterpret.

| Kind or mode | Input and presentation |
| --- | --- |
| Horizontal bar (C2a-1) | `orientation: "horizontal"` on `bar`; same data roles, C0 algorithm with swapped axes (§7.3). |
| Stacked bar (C2a-2) | `stackMode: "stacked"` or `"percent"` on `bar` with two or more series (§7.4). |
| Histogram (C2b-1) | Consumes a `bin_numeric` result table (§7.4). |
| Box plot (C2b-2) | Consumes a `box_summary` result table (§7.4). |
| Heatmap (C2b-3) | Consumes a two-key long table such as a `group_metric` result (§7.4). |

When a kind cannot express the data, the readable table remains and the reason is stated. A client that
does not know a new kind drops only that chart; the parent table stays readable. The server does not probe
client capabilities.

### 7.2 Column roles and intent

`build_chart_from_tabular_result` and the resolver keep explicit `kind` and `intent` mutually exclusive.
`composition` stays a sorted bar and never becomes a pie or a stack on its own; an explicit per-device pie
request is honoured.

Candidate roles may come from InfoTable base types, resolved units and time semantics, taxonomy
`CriticalProperties` ([AGENT-TAXONOMY](../AGENT-TAXONOMY.md)), `insightEnvelope`, `summarize_cached_result`
and skill hints; they only help identify candidates and never override the real schema. An identifier that
parses as a number is not a continuous axis. The server validates explicit bindings (existence, type, role,
range, method preconditions); with several plausible candidates the table stays and only the ambiguity is
asked about. There is no mandatory recommend call and no separate build-from-intent tool.

### 7.3 C2a-1: horizontal bar

**Wire.** `ChartBlock.orientation` is optional, `"vertical"` or `"horizontal"`, and defined only for
`kind: "bar"`. Vertical is the default and the server does not write the field for it, so vertical
payloads are byte-identical to before. Data semantics do not change with orientation: `series[].x` are
categories, `series[].y` values, `x_label` describes categories and `y_label` values (labels follow data
roles, not screen axes), and `y_reference_lines` stay on the value axis (drawn vertically). Orientation is
per chart, never per series. The numeric-history auto chart does not emit it.

**Adapter.** Accepted when absent or one of the two strings on a bar. Any other value (empty, other case,
non-string) on a bar, or the field on another kind, rejects the chart with `CHART_ORIENTATION_INVALID`.

**Tool and builder.** `build_chart_from_tabular_result` takes optional `orientation` (enum
`vertical`/`horizontal`, exact match). A resolved kind other than bar, or a value outside the enum, is
`INVALID_PARAMETERS`; when `rank`, `composition` or `compare_groups` resolve to bar, the orientation
applies. `ParlerTabularChartBuilder.buildChartBlock(…, orientation)` writes
`"orientation":"horizontal"` only for horizontal bars, on both wide and long bar paths; ordering, the stable
descending sort of `rank` (ties keep source order), the 6-series and 24-category limits,
`filledMissingCombinations` and `source` are unchanged. The `CHART_EMITTED` success JSON mirrors
`orientation`. Orientation is only applied on explicit request, never chosen automatically.

**Rendering.** The band scale is on Y (categories top to bottom in `series[].x` order, padding 0.2, inner
padding 0.08), the value scale on X. The value domain is the signed C0 domain;
`barRectGeometryHorizontal(xScale, value)` mirrors `barRectGeometry`. The zero baseline, grid and reference
lines are vertical. Value axis at the bottom, category axis on the left, not rotated.

- Category gutter width is fitted to the measured label widths (SVG probe `getComputedTextLength`, or
  `estimateTextWidth` where text cannot be measured; injectable through `options.measureText`) and clamped
  to `[48, min(160, 0.35 × viewBoxW)]`. Labels wrap at spaces, `-`, `_` or `.` into at most two lines and are
  otherwise shortened with `…`; wide glyphs and CJK names are shortened, not clipped. Detached print cards
  measure with a probe attached to `document.body` (`createTextMeasurer`).
- Height budget (`horizontalBarHeightBudget`): with `n` categories, `S` series slots, `L ∈ {1, 2}` label
  lines and line height `lh = ceil(1.3 × fs)`: `barStep = ceil(6 × (S + 0.08) / 0.92 / 0.8)`,
  `labelStep = L × lh + 4`, `step = max(barStep, labelStep)`, `innerH = max(176, ceil(step × (n + 0.2)))`,
  `h = innerH + 64`. Sub-bars are at least about 6 px thick and neighbouring labels never overlap. Examples:
  24 × 6 series → `h = 1274`; 24 × 1 with two-line labels → 936; 24 × 1 single-line → 548.
- The SVG is not capped. The card's `.chart-plot-area` has `max-height: 720px` and `overflow-y: auto`; the
  expand layer removes the cap; print outputs the full height. Keyboard query scrolls the hit item and the
  space its tooltip needs into view through all scrollable ancestors without moving focus.
- The hit model takes `orientation`: nearest hits are measured against the band; keyboard steps keep the
  data semantics of vertical bars. No zoom control for bars.

Fixtures: HB-1 long names (24 categories of 28–40 characters at 12 px and 18 px; wrapping and shortening;
budget heights 936 and 1323; no label overlap; scrolling and End reveal), HB-2 signed geometry, HB-3 6 × 24
grouped (height 1274, sub-bar ≥ 4 px, hidden series keep slots), HB-4 ties keep source order, HB-5 reference
lines, HB-6 same payload vertical vs horizontal gives identical domains, ticks, tooltips and notes, HB-7 wire
acceptance, HB-8 tool (`INVALID_PARAMETERS`, mirroring, intent). Tests: `lib/chartDrawBarHorizontal.test.mjs`,
`lib/chartWire.test.mjs`, `lib/historyHydrate.charts.test.mjs`, `lib/chartCard.test.mjs`;
`ParlerTabularChartBuilderTest`, `BuildChartFromTabularResultExecutorSuccessJsonTest`,
`BuildChartFromTabularResultToolSchemaTest`.

### 7.4 Distribution operators (D1), histogram, box plot, heatmap and stacked bar

**Common rules.** The three distribution kinds use kind-specific payload objects and never carry
`series`. All statistics come from server operators; the builder validates and copies them. The renderer
does not regroup, filter, bin or compute quantiles; it only computes display positions and shares (pie
percentages, stack accumulation, percent stacking). The chart's direct parent table is the operator output
table, so View data shows the same numbers as the chart. Each chart is one build action and does not
change the presentation limit, rescue or allowlist. `source.pointCount` is the number of bins, groups or
non-empty cells. The distribution kinds have no legend toggles, fit-Y or X zoom; card, notes, point query,
expand and print work as for other kinds. `chartBlock` is persisted and exported verbatim; hydrate uses the
same adapter.

**D1: the two distribution modes of `tabulate_cached_result`.** Implemented in
`tools/CachedTabularDistributionExecutor` (`binNumeric`, `boxSummary`). Output tables are self-describing
with scalars repeated per row. Both accept `filters` and run as **decision** modes
(`isDecisionTabulateMode`): at most 100,000 scanned rows (`SOURCE_TOO_LARGE`) and 2,000,000 cells
(`TABLE_TOO_LARGE_FOR_TRANSFORM`). Result kinds are `_INLINE` and `_EMPTY` only. Numeric output columns are
NUMBER (counts are integer-valued, for example `7.0`).

| Mode | Arguments | Rules | Output columns |
| --- | --- | --- | --- |
| `bin_numeric` | `column` (numeric); exactly one of `binEdges` (2–51 strictly increasing finite numbers) or `binCount` (1–50); `rangeMin` / `rangeMax` only with `binCount`; no `groupBy` | Method `explicit_edges_v1` or `equal_width_v1`. Equal width uses the given range, else the valid min/max; edges `min + i × (max − min) / k`, the last edge is `max` itself; a constant column gives one bin `[v − 0.5, v + 0.5]`. Bins are `[a, b)`, the last bin includes its right edge. `validCount` counts finite values, including those outside the edges; null, non-numeric and non-finite go to `excludedCount`; values outside the edges go to `belowRangeCount` / `aboveRangeCount`, so `Σcount = validCount − below − above`. `density = count / (Σcount × width)`, integrating to 1. `Σcount = 0` is EMPTY. | `binIndex, binStart, binEnd, count, density, validCount, excludedCount, belowRangeCount, aboveRangeCount, method`; `CACHED_BIN_NUMERIC_INLINE` / `_EMPTY`. |
| `box_summary` | `column` (numeric); optional `groupBy` (one column) | Method `tukey_1_5_iqr_linear_p_v1`. Per group, sorted valid values give `min, q1, median, q3, max` by `h = (n − 1)p` linear interpolation (type 7, `LinearPercentile.ofSorted`). `whiskerLow` is the smallest measured value ≥ `q1 − 1.5 × IQR`, `whiskerHigh` the largest ≤ `q3 + 1.5 × IQR`; values beyond are outliers, `outlierCount` counts all and `outliers` lists at most 20, farthest from the nearest fence first. `n = 1` makes all seven statistics equal; `IQR = 0` makes every different value an outlier. Groups keep source first-appearance order; without `groupBy` one group `All`. More than 24 groups is `TOO_MANY_GROUPS`; groups without valid values produce no row; no valid values at all is EMPTY. | `groupKey, n, excludedCount, min, q1, median, q3, max, whiskerLow, whiskerHigh, outlierCount, outliers` (JSON array text), `method`; `CACHED_BOX_SUMMARY_INLINE` / `_EMPTY`. |

Conflicting or out-of-range arguments (both or neither of `binEdges`/`binCount`, non-increasing edges,
`rangeMin ≥ rangeMax`) are `INVALID_PARAMETERS`; a non-numeric column uses the `group_metric` column-type
error. A box plot needs its own operator because whiskers and outliers depend on the raw values. A heatmap
needs no new operator: a two-key `group_metric` long table is its input.

**Source chain.** A non-empty result is written to its own derived cache and its envelope has the
`group_metric` INLINE shape: `resultKind`, `cacheId` (derived), `sourceCacheId`, real `columns`, `rows`,
`totalRows` / `returnedRows`, `rowsOmitted`, `sampleOnly`, `answerSetComplete: true`.
`TabularChartRoundHooks.parseTabulateCached` recognizes the four result kinds, so the output becomes the
`last_invoke` source and a presentation artifact (parent table). The two `_EMPTY` kinds **clear** the
chartable `last_invoke` target (`TabularChartRoundState.recordQualifyingEmptyClearingTarget` empties
`lastCacheId` and `lastInlineRows`), so an empty distribution never silently charts the previous table; a
`source: last_invoke` build then fails with `SOURCE_RESULT_NOT_TABULAR`. An explicit `cache_id` of an older
table still works. Other EMPTY kinds (`CACHED_GROUP_METRIC_EMPTY`, …) keep their behavior. "Complete"
describes only the operator output; input limits travel through the source descriptor and unknown stays
unknown.

**Build tool.** `build_chart_from_tabular_result` accepts `kind` `histogram`, `boxplot` and `heatmap`.

| Kind | Parameters | Source checks |
| --- | --- | --- |
| `histogram` | `histogramMode` `count` (default) or `density`; `title`, `xLabel`, `yLabel` | Source must have the `bin_numeric` columns with contiguous strictly increasing edges, non-negative integer counts, `Σcount = validCount − below − above > 0`, each density within relative error 1e-9 of `count / (Σcount × width)` (0 for empty bins), a registered method, identical scalars on every row, at most 50 bins. Otherwise `SOURCE_SHAPE_MISMATCH` with a `recoveryHint` naming `bin_numeric`; nothing is corrected. Column bindings, series, reference lines, pie, orientation and time-window parameters are `INVALID_PARAMETERS`. |
| `boxplot` | `title`, `xLabel`, `yLabel`; `yReferenceLines` (spec lines on the value axis; more than 12 is `TOO_MANY_REFERENCE_LINES`) | Source must have the `box_summary` columns with `min ≤ whiskerLow ≤ q1 ≤ median ≤ q3 ≤ whiskerHigh ≤ max` finite, `n ≥ 1` and non-negative integer `excludedCount`/`outlierCount`, `0 ≤ outlierCount ≤ n`, parseable `outliers` of length exactly `min(outlierCount, 20)`, each outlier beyond the whiskers and inside `[min, max]`, whiskers equal to min/max when `outlierCount = 0`, a registered method, unique non-empty `groupKey`, at most 24 groups. Otherwise `SOURCE_SHAPE_MISMATCH` naming `box_summary`. Success JSON has `groupCount`. |
| `heatmap` | Long-table bar binding: `xColumn` = column dimension, `seriesColumn` = row dimension, `yColumn` = cell value (all required); `yLabel` becomes `valueLabel` | Rows ≤ 24 and columns ≤ 48, else `TOO_MANY_CATEGORIES` with `details.dimension`; rows and columns in first-appearance order (sort with `tabulate` `sorts`); a duplicate `(row, column)` is `DUPLICATE_CELL`; missing combinations and null values become `null` cells, never zero; all missing is `HEATMAP_ALL_MISSING`; a text value column is `Y_COLUMN_NOT_NUMERIC`; reference lines are `INVALID_PARAMETERS`. Success JSON has `rowCount2d`, `colCount`, `missingCount`, `seriesCount = 0`. Only explicit `kind`; no intent resolves to it. |

The `distribution` intent routes by source shape: bin columns → `histogram`, box columns → `boxplot`,
otherwise `DISTRIBUTION_REQUIRES_BINNED_SOURCE` with a hint naming both modes. The build tool never bins
implicitly. `composition` and `compare_groups` never stack.

**Wire.**

```json
{ "kind": "histogram", "x_label": "Temperature (°C)", "y_label": "Count",
  "histogram": { "edges": [60, 62, 64, 66], "counts": [12, 40, 9], "densities": [0.09836065573770492, 0.32786885245901637, 0.07377049180327869],
                 "mode": "count", "validCount": 61, "excludedCount": 2,
                 "belowRangeCount": 0, "aboveRangeCount": 0, "method": "equal_width_v1" } }

{ "kind": "boxplot", "x_label": "Device", "y_label": "Temperature (°C)",
  "boxplot": { "method": "tukey_1_5_iqr_linear_p_v1",
               "groups": [ { "key": "Oven-01", "n": 120, "excludedCount": 0, "min": 58.1, "whiskerLow": 60.2,
                             "q1": 62.0, "median": 63.1, "q3": 64.4, "whiskerHigh": 67.9, "max": 71.5,
                             "outliers": [71.5, 58.1], "outlierCount": 2 } ] },
  "y_reference_lines": [ { "y": 70, "label": "USL", "role": "usl" } ] }

{ "kind": "heatmap", "x_label": "Hour", "y_label": "Device",
  "heatmap": { "rows": ["Oven-01", "Oven-02"], "cols": ["00", "01", "02"],
               "values": [[63.1, 63.4, null], [61.0, 60.8, 61.2]],
               "valueLabel": "Avg temperature (°C)", "missingCount": 1 } }

{ "kind": "bar", "stackMode": "stacked", "series": [ … ] }
```

`stackMode` is defined only for bars, `"stacked"` or `"percent"`; grouped is the default and is not written,
so grouped payloads are byte-identical. It may appear together with `orientation`. `y_reference_lines` are
not allowed on histogram and heatmap. Limits: CHART_CONTRACT `HIST_MAX_BINS = 50`, `BOX_MAX_GROUPS = 24`,
`BOX_MAX_SHOWN_OUTLIERS = 20`, `HEATMAP_MAX_ROWS = 24`, `HEATMAP_MAX_COLS = 48` (§3.0f–§3.0h).

**Adapter.** The adapter mirrors the builder's local invariants so live and replayed payloads get the same
decision; it checks relations between given numbers and never recomputes statistics. Codes:
`CHART_HISTOGRAM_INVALID` (edges, counts, sums, densities within 1e-9, mode, method, 1–50 bins),
`CHART_BOXPLOT_INVALID` (1–24 groups, unique keys, order relation, counts, outlier list, method),
`CHART_HEATMAP_INVALID` (1–24 unique rows, 1–48 unique columns, `rows × cols` matrix of finite numbers or
`null`, at least one non-null, `missingCount` equal to the null count), `CHART_STACK_MODE_INVALID` (not a
bar, a value other than `stacked`/`percent`, fewer than two series, or negative values under `percent`). A
new kind carrying `series`, or an existing kind carrying a new payload object, is rejected. A heatmap
`null` is explicit missing data and does not conflict with the V1 rule for `series[].y`.

**Rendering.**

- **Histogram** (`drawHistogram`): linear X, bins drawn between their real edges (unequal bins are
  unequally wide), height from the chosen mode, full width × 240 (§4.6), ticks fitted by
  `fitLinearAxisLabels`. With unequal bins, compare shapes with `density`. One hit item per bin with
  interval (last bin marked as closed), count and density. Notes list method, bin count, height meaning,
  valid, excluded and out-of-range counts.
- **Box plot** (`drawBoxplot`): vertical bands (§4.6 band cap with `S = 1`); the value domain covers
  min/max, shown outliers and reference lines. Box `q1–q3`, median line, capped whiskers, hollow outliers.
  Hit items: one per box (group, n and excluded, five numbers, whiskers, outlier summary including "N more
  not shown") and one per shown outlier. Notes list ten statistics per group, method, whisker rule and
  outlier counts.
- **Heatmap** (`drawHeatmap`, `appendHeatLegend`): row labels on the left (horizontal-bar gutter rules),
  column labels below, horizontal when they fit, otherwise rotated and thinned by
  `ceil(1.25 × tick size / cell width)` with first and last kept, never shrunk. Cell width
  `clamp(floor(innerW / cols), 20, 64)`, height `clamp(round(width × 0.62), 20, 40)`; the card scrolls
  horizontally when needed and vertically above 720 px; the expand layer uses its own width with horizontal
  scrolling. Print uses `W_print = min(W, 760)`, cell width `max(4, floor(innerW / cols))`, height in
  `[8, 40]`, no scrolling or clipping, labels at the Theme tick size, rows no lower than one label line.
  Colour scale ([`chartHeatColor`](../../../parler-ui/lib/chartHeatColor.js), no new tokens): sequential
  "plot surface → series slot 0" for all non-negative or all non-positive data, diverging and symmetric
  around 0 ("slot 1 ← surface → slot 0") across zero, midpoint colour for a constant. Missing cells use a
  grid-coloured hatch. The colour bar shows min/max (and 0 when crossing zero), `valueLabel` and a "No data"
  sample. Cells carry `data-parler-palette-role="heat"`, `data-heat-t` and `data-heat-scale` so print
  recolours them. Values are written in cells of at least 40 × 24; every cell is readable by keyboard and
  pointer (row, column, value or "No data").
- **Stacked bar** (`barStackTotals`, `barStackSegments`): `stacked` accumulates positive values upward and
  negative values downward from zero (left/right when horizontal), with the domain from the positive and
  negative sums; `percent` has domain `[0, 100]` and share = value / sum of all emitted series in the
  category. A category summing to 0 draws no bar and is marked "no share". One hit item per segment with
  value, share and category total. Hiding a series re-packs the remaining segments but keeps shares,
  denominators and the default Y domain; the view note lists hidden series.

**Build tool for stacking.** Optional `stackMode` (enum `grouped`, `stacked`, `percent`): a resolved kind
other than bar, a value outside the enum or fewer than two series (single `yColumn` without
`seriesColumn`) is `INVALID_PARAMETERS`; `percent` with a negative value is `STACK_PERCENT_NEGATIVE`. Only
non-grouped values are written. Stacking is only applied on explicit request.

**Method identifiers.** `explicit_edges_v1`, `equal_width_v1` and `tukey_1_5_iqr_linear_p_v1` are persisted
with result tables and ChartBlocks; a change to binning, quantile or whisker rules requires a new
identifier.

Fixtures:

| Id | Case | Assertion |
| --- | --- | --- |
| BN-1 | `[0, 1, 1, 2, 2, 2, 3]`, edges `[0, 1, 2, 3]` | Counts `[1, 2, 4]`; edge values counted once; last bin includes 3. |
| BN-2 | Edges `[0, 1, 3, 10]` | `Σ(density × width) = 1` (1e-12). |
| BN-3 | `binCount 4` over `[10 … 20]`; constant 5 | Edges 10, 12.5, 15, 17.5, 20; constant gives `[4.5, 5.5]`. |
| BN-4 | null, text, NaN, values outside explicit edges | Four counts correct; out-of-range values in no bin; all out of range is EMPTY. |
| BN-5 | Both/neither of `binEdges`/`binCount`; non-increasing edges; `binCount 51`; `rangeMin ≥ rangeMax` | `INVALID_PARAMETERS`. |
| BX-1 | Odd and even samples | `q1/median/q3` equal `group_metric` percentile 25/50/75. |
| BX-2 | `n = 1`; `IQR = 0` with one different value | Seven equal statistics; the different value is the one outlier. |
| BX-3 | Extreme values; 30 outliers | Whiskers are measured values, not fences; 20 listed, `outlierCount = 30`, farthest first. |
| BX-4 | Three groups, one without valid values; 25 groups | Invalid group produces no row; 25 groups is `TOO_MANY_GROUPS`. |
| SC-1 (D1) | Old table A, then an operator output B | `last_invoke` resolves to B; B is the parent table; envelope has derived `cacheId`, `sourceCacheId`, real columns and counts. |
| SC-2 (D1) | Old table A, then EMPTY from each mode; limited input | `last_invoke` builds fail with `SOURCE_RESULT_NOT_TABULAR` instead of charting A; explicit `cache_id` A still works; `CACHED_GROUP_METRIC_EMPTY` unchanged; input limits kept in the descriptor. |
| SC-3 (D1) | 86,400 rows; more than 100,000 rows; more than 2,000,000 cells | Success; `SOURCE_TOO_LARGE`; `TABLE_TOO_LARGE_FOR_TRANSFORM`. |
| HG-1 | BN-1 / BN-2 outputs | Payload equals the table; `density` mode changes only `mode`; `pointCount` = bins. |
| HG-2 | Plain table; broken `binEnd`; inconsistent sums; wrong densities; unregistered method; extra `xColumn` | `SOURCE_SHAPE_MISMATCH` naming `bin_numeric`; the last is `INVALID_PARAMETERS`. |
| HG-3 | Unequal bins | Widths proportional to bins, no gaps, last bin closed, viewBox `W × 240`. |
| BP-0 | `outlierCount > n`; wrong outlier list length; outlier inside whiskers or beyond max; whiskers not at min/max with no outliers | `SOURCE_SHAPE_MISMATCH` naming `box_summary`. |
| BP-1 | BX-1 / BX-3 outputs with a `usl` line | Geometry matches statistics; reference line in domain; hidden outliers disclosed. |
| BP-2 | 2 and 24 groups at 1200 px | 2 groups narrowed and centred; 24 full width. |
| HM-1 | 2 × 3 long table missing one combination | `null` cell, `missingCount = 1`, hatch, "No data", no zero fill. |
| HM-2 | All positive; crossing zero; constant | Sequential, symmetric diverging, midpoint; colour bar ticks equal data extremes. |
| HM-3 | 25 rows; 49 columns; duplicate cell | `TOO_MANY_CATEGORIES` twice; `DUPLICATE_CELL`. |
| HM-4 | 24 × 48 at 480 px card, expand and print; 2 × 3 at 1200 px | Card and expand: 20 px cells, scroll to last column, text not shrunk. Print: all 1,152 cells inside the page, thinned labels keep first and last, no cell values. 2 × 3: 64 px cells, centred. |
| HM-5 | Any heatmap printed | Cells and colour-bar stops recoloured with the print palette; no unknown role. |
| SK-1 | Two series with mixed signs | Positive and negative stacks separate and contiguous; domain from sums; horizontal swaps axes. |
| SK-2 | Three series, one category summing to 0 | Shares sum to 100; zero-sum category has no bar and reads "no share"; domain `[0, 100]`. |
| SK-3 | SK-2 with a hidden series | Remaining segments re-pack; shares, denominators and Y domain unchanged; view note lists it. |
| SK-4 | `stackMode` absent / valid / invalid / on line / single series / `percent` with negatives | Absent not written; adapter `CHART_STACK_MODE_INVALID`; tool `INVALID_PARAMETERS`; `STACK_PERCENT_NEGATIVE`. |
| WK-1 | Each new kind: valid payload and every broken variant, including shape-valid but contradictory statistics; `series` present | Valid accepted; each variant rejected with its code; history drops only that chart. |
| IN-1 | `distribution` on bin table, box table, plain table | `histogram`, `boxplot`, `DISTRIBUTION_REQUIRES_BINNED_SOURCE`. |

Tests: `CachedTabularBinNumericExecutorTest`, `CachedTabularBoxSummaryExecutorTest`,
`TabularChartRoundHooksTest`, `ParlerTabularChartHistogramTest`, `ParlerTabularChartBoxplotTest`,
`ParlerTabularChartHeatmapTest`, `ParlerTabularChartBuilderTest`,
`BuildChartFromTabularResultExecutorSuccessJsonTest`, `BuildChartFromTabularResultToolSchemaTest`; UI
`lib/chartDrawHistogram.test.mjs`, `lib/chartDrawBoxplot.test.mjs`, `lib/chartDrawHeatmap.test.mjs`,
`lib/chartDrawBarStacked.test.mjs`, `lib/chartWire.test.mjs`, `lib/chartHitModel.test.mjs`,
`lib/chartCard.test.mjs`, `lib/historyHydrate.charts.test.mjs`. Parameter and output reference:
[cached_tabular_tools.md](../cached_tabular_tools.md) and
[cached-table-decision-tools.md](../cached-table-decision-tools.md).

## 8. Multi-chart layout and chart groups

### 8.1 C3a: layout of existing artifacts

`groupArtifactsForLayout(ordered, groups)` in
[`artifactPresentation`](../../../parler-ui/lib/artifactPresentation.js) works on the result of
`orderArtifactsForDisplay`: two or more adjacent chart artifacts form one `chart-grid` slot; everything
else keeps its own slot. A table between two charts breaks the adjacency; a paired parent table appears
once, before its chart. Nothing is reordered, copied or hidden. The grid rule is
`artifactGridColumns(availableWidth, chartCount)` with `ARTIFACT_GRID_GAP = 16`,
`ARTIFACT_GRID_MIN_COLUMN = 360` and `ARTIFACT_GRID_MAX_COLUMNS = 2`: two columns only when each column is at
least 360 px after the gap and there are at least two charts. At runtime CSS implements it:
`.chart-grid { grid-template-columns: repeat(auto-fit, minmax(max(360px, calc(50% - 8px)), 1fr)); gap: 16px }`.
The grid element is `div.chart-grid[data-parler-chart-grid=n][data-parler-chart-grid-key]`.

Without an explicit group, charts share only layout: each card measures its own column width (§4.6 `W`)
and keeps its own view state, coordinates, colours and filters. Two charts with similar titles are not
treated as one dataset. Legacy history rows without `row.artifacts` go through
`buildArtifactsFromLegacyBuckets` and the same projection. The expand placeholder stays in the grid; print
outputs grid members in one column at bubble width.

### 8.2 Chart groups

A chart group is a lightweight manifest inside one answer that references charts by `chartId`; it copies
no data. It carries a stable identity and a monotonically increasing revision, a title, 2–6 ordered member
slots, a layout hint, per-member states (`pending`, `ready`, `no-data`, `error`, `cancelled`) and a summary.
Optionally it declares a shared category dimension for colours (§8.6, §8.7). Members never share axes,
data or filters.

States only move forward from `pending` to a terminal state. A `ready` member must resolve to a real chart
artifact; when the declaration arrives before the chart, the slot waits. When the chart arrives before the
manifest, it is first shown as an ordinary card and moved into its slot when the manifest arrives, keeping
the same instance and view state. Members still pending at turn end are converged by the server; if the
client loses the connection before the final revision, it only reports the interruption.

### 8.3 Sources and budgets

Every chart binds its own actual `cacheId`; derived charts bind the derived result. `last_invoke` is for
the explicit "most recent single table" case. `answerSetComplete`, the allowlist, the one-shot rescue and
the iteration budget are unchanged by groups; each member is a separate build action within the
presentation limit, and member charts produced by the rescue are not bound to a group. The agent never
reopens data tools just to fill a group.

### 8.4 Shared colours and visual linking

Colours are shared only inside a group that explicitly declares a shared category dimension. The key is
the category text within that dimension; display names are only labels. Slot order is fixed by the group
and never reassigned by zero filtering, missing categories, arrival order or legend toggles. Same-named
categories in different groups or answers are not linked. Slots map to the existing Theme series colours,
also in print. Linking is visual only (same-category highlight) and never changes any member's data,
denominators or Y domain. Groups share no X or Y axes and no cursor.

### 8.5 C3b-1: chart group identity, states and replay

**Producer.** Built-in tool `declare_chart_group` (`DeclareChartGroupToolSchema`,
`DeclareChartGroupExecutor`, registered in `BuiltInTools`): `title` (1–120 characters), `members` (2–6 items
`{ key, name }`, `key` matching `^[a-z0-9_-]{1,32}$` and unique, `name` 1–80 characters), optional `layout`
(`auto` default, `stack`, `grid`), optional `sharedCategoryDimension` (§8.7). Members are always charts.
One group per user request: a second call is `CHART_GROUP_LIMIT`; bad member counts, keys, names or title
are `INVALID_PARAMETERS` with `details.reason`. Success returns
`{ status: "success", code: "CHART_GROUP_DECLARED", groupId: "g1", revision: 1, memberKeys, members }`;
`groupId` comes from the same per-request counter as `chartId` (`TabularChartRoundState`, continued across
approval pauses). The declaration is not a presentation action, but the member cap equals that budget.

`build_chart_from_tabular_result` takes optional `groupMemberKey`, which must name a pending member of the
declared group, else `INVALID_PARAMETERS` with `details.reason` `no_group_declared`, `unknown_member` or
`member_not_pending`. A successful build binds the member to the new `chartId`. `ChartBlock` gets no new
field.

**Wire.** Frame `{ "type": "chart_group", "conversation_id", "request_id", "group": ChartGroupManifest }` on
the same channel and request as `chart` frames (CHART_CONTRACT §2.6, §3.5):

```json
{ "type": "chart_group", "conversation_id": "agent-thread01", "request_id": "8fecb285-…",
  "group": { "groupId": "g1", "revision": 3, "title": "Oven temperature vs pressure", "layout": "auto", "final": true,
             "members": [ { "key": "temp", "order": 0, "name": "Temperature", "expectedType": "chart", "state": "ready", "chartId": "c1" },
                          { "key": "pres", "order": 1, "name": "Pressure", "expectedType": "chart", "state": "no-data", "code": "EMPTY_AFTER_FILTER" } ],
             "summary": { "expected": 2, "ready": 1, "noData": 1, "error": 0, "cancelled": 0, "final": true } } }
```

Each state change resends the whole manifest with revision + 1. Member order and slots never change.
`ready` carries a `chartId` already sent in this answer; `no-data` and `error` carry `code` (optional
`message` ≤ 200 characters); `pending` and `cancelled` carry no `chartId`. The summary matches the members
and `expected = members.length`. `final: true` appears only on the last revision.

**State machine** (`tools/ChartGroupState`, attached to `TabularChartRoundState` and deep-copied into the
approval `Snapshot`). Revision 1 (all pending) is sent at declaration, before any member chart. A dirty
group is sent before the chart frames are drained; after a member's `chart` frame is sent successfully,
`ParlerToolArtifactWireEmitter` sends the `ready` revision. A failed bound build converges the member to
`no-data` for `EMPTY_AFTER_FILTER`, `HEATMAP_ALL_MISSING` or `CHART_FALLBACK`, else to `error` (including
`PRESENTATION_ACTION_LIMIT` when `AgentLoop` blocks the build, via `noteBlockedBuild`). At turn end
(`ChartGroupTurnHooks`, at the same points as the end-turn chart rescue) remaining pending members become
`cancelled` when the user cancelled and `error` / `MEMBER_NOT_PRODUCED` otherwise, and the `final` revision
is sent. A failed chart downlink never sends `ready`; the member ends as `error` / `DOWNLINK_FAILED`.
Frames are not retransmitted; replay uses history.

**Turn-end order.** Every entry point runs `AgentToolContext.clear()` in the `finally` of `loop.run`, which
removes group state, downlink flags and stream ids before it is known whether the turn completed. The first
statement of that `finally` is therefore `captureBeforeContextClear()`, and `onTurnEnd` consumes the capture.
The final manifests are read once through `AgentToolContext.takeChartGroupsJsonForFinalAssistantRow()`.

**Client idempotency.** Identity is `(conversation, request, groupId)`; only a higher revision is
accepted, and nothing after `final`. Cancelled requests ignore all group frames. If a session error or
supersession interrupts a non-final group, the card says the transport was interrupted and the server
result is unknown, and member states are left unchanged.

**History.** `AgentMessageStreamAppender` writes `chartGroupsJson` (field
`FIELD_CHART_GROUPS_JSON`, DataShape `AgentMessageData` field 11) on the final assistant row only, holding
the final manifests. `AgentMessageStreamHistoryExporter.groupsFromTail` writes `groups[]` into the
`ai-parler-history-v1` assistant row. If the field is missing but a `CHART_GROUP_DECLARED` tool row exists,
the group is rebuilt with every member `error` / `TURN_INCOMPLETE` and `final: true`. `groups[]` links to
`charts[]` by `chartId`, so the export keeps the persisted `chartId` of tabular charts
(`chartBlockFromChartEmittedToolResult` with `assignNewChartId = false`); numeric-history charts cannot be
members. A user-cancelled turn writes no final assistant row, so its history group is rebuilt as
`TURN_INCOMPLETE` although the live frame said `cancelled`. `historyHydrate` validates through
`asChartGroupManifest`; an invalid manifest drops only the group. Replay never re-chooses kinds, infers
intent or changes member states.

**UI.** `asChartGroupManifest` (`chartGroupRejection`) checks non-empty `groupId`, positive revision,
string title, known layout, 2–6 members with unique keys, `order` exactly 0..n−1, known states, `ready` with
a unique non-empty `chartId`, `code` on `no-data`/`error`, and a consistent summary; anything else rejects
the whole manifest with `CHART_GROUP_INVALID`. `wireToUiEvent` maps the frame to
`assistant.chartGroup { requestId, group }`; the reducer (`lib/chatSession.js`, rule 5b in
UI_CLIENT_PROTOCOL) upserts it into the row's `groups[]` and clears `activity` and `rateControlWaiting`.

**Rendering.** In `groupArtifactsForLayout`, referenced charts move into a `{ type: "chart-group", group,
slots }` slot placed at the first arrived member's position (or at the end of the answer's artifacts).
Unreferenced charts and tables use C3a. The card (`section.chart-group`, parts `chart-group`,
`chart-group-title`, `chart-group-slot`, `chart-group-summary`) shows the title, slots by `order` (the chart,
"waiting to load" for `ready` without a chart yet, "waiting for <name>" for `pending`, a status text with
the code otherwise) and a summary line ("2 / 3 ready · 1 no data"). `stack` is one column; `grid` and
`auto` use the C3a column rule. Print outputs the title and members in order, with status texts for members
that are not ready.

**Compatibility and budget.** Clients that do not know `chart_group` ignore the unknown frame type and show
single cards; readers that do not know `groups[]` do the same. Limits: one group per request, 2–6
members, at most 2 + 2 × members frames, `chartGroupsJson` at most 8 KB (the exporter drops `message`
fields and, if still too large, omits the field so the group is rebuilt as `TURN_INCOMPLETE`).

Fixtures: GR-1 normal path (revision 1 before charts, a revision after each chart, final summary
`{2,2,0,0,0}`, Java frames equal the UI state), GR-2 convergence (`ready`, `no-data` with code, `error` /
`MEMBER_NOT_PRODUCED`; `cancelled` on user cancel; one `final`), GR-3 arrival orders (chart first, manifest
first, `ready` before chart give the same DOM and keep view state), GR-4 idempotency (duplicates, stale and
post-final revisions ignored), GR-5 validation and budget (tool reasons, `CHART_GROUP_LIMIT`, adapter
`CHART_GROUP_INVALID`), GR-6 history (export equals the live final manifest; `TURN_INCOMPLETE` rebuild; no
`groups[]` without either), GR-7 card and print, GR-8 interruption. Tests: `ChartGroupStateAndDeclareTest`,
`ChartGroupTurnHooksTest`, `ParlerToolArtifactWireEmitterGroupTest`, `AgentMessageStreamHistoryExporterTest`
(including `exportedReadyMembersResolveToExportedChartsByChartId`), `lib/chartGroup.test.mjs`,
`lib/parlerUiChartGroup.test.mjs`, `lib/historyHydrate.charts.test.mjs`.

### 8.6 Shared category colours: behavior

Without shared colours, each chart assigns colour slots in its own slice or series order, so the same
colour can mean different categories in two side-by-side charts (for example Setup in one pie and Running
in the other). Inside a group that declares a shared category dimension:

- **Explicit only.** Colours are shared only when the producer declares the dimension. Undeclared groups,
  charts outside groups and same-named categories across groups or answers keep per-chart colours.
- **Category key.** Within the dimension, the key is the category text sent by the server: pie slice labels,
  or series names for multi-series bar, line and scatter. `Other` is its own key. Histogram, boxplot and
  heatmap have no categorical colours.
- **Append-only slots.** The server assigns slots in first-assignment order and never changes an assigned
  slot. A chart that is already drawn never changes colour when later members arrive.
- **One mapping everywhere.** Marks, legend, tooltip and keyboard swatches, data view and print use the same
  mapping.
- **Beyond the palette.** More categories than distinguishable slots never reuse colours as identity
  (§8.7).
- **Same-category focus.** Hovering or focusing a category in one member highlights the same category in
  the other members and dims the rest, without changing data, denominators, percentages or Y domains. It
  is transient, not persisted and not printed.
- **History.** The mapping travels with the final manifest in `chartGroupsJson` and replays identically.
  Groups without a mapping use per-chart colours.

Reference scenario: members A = `[Setup, Down, Running]` and B = `[Running, Down]` declaring one "utilization
state" dimension give Running and Down the same colours in both charts and Setup its own colour, for
either arrival order; a zero-value category in B takes no slot; hiding Down in A leaves B unchanged; a later
member C = `[Idle, Running]` gives Idle the next free slot; replay and print match; without the declaration
both charts use per-chart colours.

### 8.7 C3b-2a: shared category colours and focus

**Declaration.** `declare_chart_group` takes optional `sharedCategoryDimension` (string, 1–80 characters
after trimming; empty or too long is `INVALID_PARAMETERS` with `details.reason = shared_dimension`), echoed
in the result and stored in `ChartGroupState`. `build_chart_from_tabular_result` gets no new parameter: when
`groupMemberKey` binds successfully, the executor extracts keys from the built `ChartBlock`.

**Keys and normalization** (`ChartGroupState.categoryKeys`, `normalizeCategoryKey` on both server and
client). Pie: labels of slices with a finite positive `y` (zero and non-positive slices produce no key).
Bar, line and scatter with at least two series: each `series[].name`. Single-series bar/line/scatter,
histogram, boxplot and heatmap produce no keys. A key is the label with leading and trailing whitespace
removed and internal runs of whitespace collapsed to one space, **case-sensitive**; an empty result produces
no key. No other normalization or similarity matching exists.

**Manifest fields** (CHART_CONTRACT §3.5). Group-level `sharedCategories: { dimension, keys }`, where
`keys[i]` owns slot `i`, keys are unique and non-empty, at most 24; present whenever a dimension was
declared (`keys: []` before any member). Member-level boolean `colorShared`: `true` when all of the member's
keys are in `keys`; absent or `false` means per-chart colours. `ChartBlock` is unchanged.

**Assignment timing.** On a successful bind (`CHART_EMITTED`), `bindMemberCategories` appends the chart's
new keys in chart order, sets `colorShared = true` and marks the group dirty. Because a dirty group is sent
before the chart frames, the revision carrying the member's mapping always arrives before that member's
`chart` frame, so every chart is drawn with its final slots the first time. Frame budget: at most
2 + 2 × members. The final revision carries the full mapping.

**Cap.** If appending a member's keys would exceed 24 (the Theme series slot count and the modulus of
`chartSeriesSlot`), no key is appended, `keys` stays unchanged and that member gets `colorShared = false`
and per-chart colours; other members are unaffected. The UI shows "Colours not shared: more than 24
categories in this group" (`SHARED_COLOR_CAP_NOTE`) in that member's notes and tooltip.

**Client.** `chartGroupColorRejection` validates the extension; an invalid extension is dropped alone
(`CHART_GROUP_COLOR_INVALID`) and the group still shows. `groupArtifactsForLayout` gives each slot
`colorKeys` (the group's keys when `colorShared === true`, else `null`) and `colorNote`;
`chartColorContextFor` passes the same `colorKeys` to slots, the expand layer and print. `drawChart`,
`chartLegendItems(chart, theme, colorKeys)` and `renderChartPrintCard({ …, colorKeys })` all use
`resolveSeriesSlot(categoryText, fallbackIndex, colorKeys)` in
[`chart-theme.js`](../../../parler-ui/components/chart-theme.js): a key in `colorKeys` takes its index,
anything else takes `chartSeriesSlot(fallbackIndex)` exactly as without groups. Marks carry
`data-parler-series-slot` and `data-parler-category`.

**Focus linking.** A member card dispatches `chart-category-highlight` (`detail.key`, or `null` on leave)
from legend hover/focus, pointer hits and keyboard query. The widget keeps a transient
`highlightedCategory` per `groupId` (not in the view state, history or print) and passes
`highlightCategory` to every member; cards with `colorShared === true` dim other categories (opacity 0.35,
`data-parler-category-dimmed`). Escape, blur and pointer leave clear it.

**History and compatibility.** The mapping replays from `chartGroupsJson` → `groups[]`. A `TURN_INCOMPLETE`
rebuild has no mapping. Clients that know only C3b-1 ignore both extension fields.

Fixtures: SC-1 the reference scenario in both arrival orders (mapping revision before each chart frame,
equal slot attributes in both pies), SC-2 zero values and `Other`, SC-3 no reassignment on hide, fit-Y,
reorder or zero filtering, SC-4 late member, SC-5 cap, SC-6 fallbacks and invalid extensions, SC-7 kind
coverage, SC-8 focus linking, SC-9 frame order through the emitter's send seam, SC-10 real turn-end order
(`captureBeforeContextClear` → `AgentToolContext.clear()` → `onTurnEnd`), SC-11 end-to-end history using the
exported fixture `parler-ui/lib/fixtures/chart-group-shared-colors.history.json` (the Java test asserts the
committed file equals a live export). Tests: `ChartGroupStateAndDeclareTest`,
`ParlerToolArtifactWireEmitterGroupTest`, `ChartGroupTurnHooksTest`, `AgentMessageStreamHistoryExporterTest`,
`lib/chartSharedColors.test.mjs`, `lib/parlerUiChartSharedColors.test.mjs`.

## 9. History and compatibility

Chart groups, cross-type order and member states are stored as bounded structured fields of the
AgentMessageStream rows ([Appender](../../../parler-agent/src/main/java/com/thingworx/things/agent/AgentMessageStreamAppender.java),
[HistoryExporter](../../../parler-agent/src/main/java/com/thingworx/things/agent/AgentMessageStreamHistoryExporter.java))
and hydrated by the UI, never appended to free text that might be truncated.

History replay uses the final ChartBlock and saved results as they were; it never reruns the intent
resolver, so later default kinds cannot change old answers. Chart-generation intent is not persisted.

Hover, keyboard focus, selection, hidden series, expand and zoom live only for the widget lifetime; loaded
history starts from the full view. When a cache has expired, saved charts and their source limits still
show; refetching goes through permission and source checks again.

Single-chart events and the legacy `charts` / `tables` buckets remain a readable fallback; clients that
understand structured artifacts and groups use them, and old history keeps the legacy projection.

## 10. Output

### 10.1 Scope

Output is the answer print of the Composer (browser print, including print to PDF). There is no per-chart
SVG or PNG download and no server-side PDF report.

### 10.2 Print

Print reuses the answer print entry and the Theme print palette. Every printed chart has its title,
analysis range and time zone, unit, legend, method and limits; interactive buttons and hover are not
printed, and nothing essential exists only in a tooltip. Charts are printed in the full analysis range
with all series; a differing screen view is stated in a print note (§4.5).

Print output is built from the artifacts and their render model, not by cloning whatever chart happens to
be mounted: collapsed, expanded or unmounted members are never lost, members of a grid print one per row at
bubble width, group members print in order with status texts for members that are not ready, and narrow
layouts are not shrunk into thumbnails. Card titles, legends, meta lines and expanded data notes are part
of the printed card; `lib/assistantResponseActions.js` carries the print CSS, rewrites palette roles
(`series`, `axis`, `grid`, `heat`, …) for the print Theme and fails on an unregistered role.

## 11. Performance

### 11.1 Performance strategy

Point query uses [`chartHitModel`](../../../parler-ui/lib/chartHitModel.js): binary search on X-ordered
points, then a bounded scan on both sides to cover duplicate X, steep neighbours and closer points beyond
the immediate neighbours; bands for bars, angles for pies, rectangles for heatmap cells. Interaction updates
redraw from state, and Theme or resize changes never trigger tool or network calls. Line and scatter draw
one marker per emitted point within the existing 5,000-point limits; that limit is not raised to claim
large-data support, and the renderer cannot recover data a query did not fetch. History overlay
down-sampling is uniform per series (§2).

## 12. Code map

| Area | Server (`parler-agent/src/main/java/com/thingworx/things/agent/`) | Client (`parler-ui/`) |
| --- | --- | --- |
| Building and intent | `ParlerTabularChartBuilder`, `ParlerTabularChartIntentResolver`, `tools/BuildChartFromTabularResultExecutor`, `tools/BuildChartFromTabularResultToolSchema`, `tools/TabularChartSourceResolver` | — |
| Distribution operators | `tools/CachedTabularDistributionExecutor`, `tools/CachedTabularToolsExecutor`, `tools/TabularChartRoundHooks`, `tools/TabularChartRoundState` | — |
| Wire and history | `ParlerChartWireSupport`, `ParlerChartGroupWire`, `ParlerToolArtifactWireEmitter`, `AgentMessageStreamAppender`, `AgentMessageStreamHistoryExporter` | `lib/wireAdapter.js`, `lib/chatSession.js`, `lib/historyHydrate.js`, `lib/types.js` |
| Chart groups | `tools/DeclareChartGroupExecutor`, `tools/DeclareChartGroupToolSchema`, `tools/ChartGroupState`, `ChartGroupTurnHooks` | `lib/artifactPresentation.js`, `parler-ui.js` |
| Card and rendering | — | `components/parler-ui-chart.js`, `components/chart-draw.js`, `components/chart-theme.js`, `lib/chartSizePolicy.js`, `lib/chartHeatColor.js` |
| View and interaction | — | `lib/chartViewState.js`, `lib/chartHitModel.js`, `lib/chartXDomain.js`, `lib/chartTimeFormat.js`, `lib/chartDataNotes.js`, `lib/keyboardReachable.js` |
| Print | — | `lib/assistantResponseActions.js`, `parler-ui.js` (`buildAssistantPrintHtml`) |

Related documents: [chart-intent.md](../chart-intent.md) (intents and kinds exposed to the model),
[flexible-chart-solution.md](../../architecture/flexible-chart-solution.md),
[prompt-to-chart.md](../prompt-to-chart.md) (chart rescue), and
[chart-extensions-roadmap.md](../../ui/chart-extensions-roadmap.md) (open questions about limits and SPC
layers).

## 13. Verification

### 13.1 Behavior covered by tests

| Area | Assertions |
| --- | --- |
| Signed values and reference lines | The §5.1 data sets: finite geometry, non-negative sizes, baseline and reference lines visible, value proportions correct. |
| Time and units | Irregular spacing 1 : 59; readable cross-day and DST times; absolute, elapsed and normalized never mixed. |
| Input quality | The §5.3 matrix over all kinds and X modes, live and history, legal zero versus illegal null. |
| Single chart | Keyboard and pointer query; legend hide, pie focus, all hidden, reset, expand and close. |
| Size policy | LS-1..LS-6. |
| Widget layout | 320 / 480 / 768 / 1200 px widths, long titles and names, 6 series, light and dark, two instances on one page. |
| Combinations | Two and four pies, line + pie, shared and separate parent tables. |
| Groups and colours | GR-1..GR-8 and SC-1..SC-11 (§8.5, §8.7). |
| History | Group order and states replay identically; old messages without new fields stay readable. |
| Print | Full view and view notes; collapsed, grid, expanded and unmounted members all printed. |

### 13.2 Local verification

UI tests run from `parler-ui/` (some tests read sources by paths relative to that directory):

```bash
cd parler-ui
npm test
```

`scripts.test` in `parler-ui/package.json` is an **explicit list** of test files, not a glob: a new or renamed
test file must be added to it, and the `npm test` output should show its cases. Widget builds use
`./build-widget.sh` from the repository root. DOM tests do not replace checks in a real browser for size,
focus, gestures and print.

Java changes:

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

This needs a compatible Java and a populated `parler-agent/twx-lib/all`.
