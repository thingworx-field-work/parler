# Chart limits and reference lines

**Normative:** [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md) (`ChartBlock`, wire `chart` frame,
`y_reference_lines`). This page is a short, non-normative summary of how limits and SPC lines appear on charts.
The chart product design is [Chart enhancement](../agent/nearterm/chart-enhancement.md).

**Related:** [`statistic-practice.md`](../operations/statistic-practice.md) (SPC context),
[`use-cases.md`](../operations/use-cases.md) (prompts vs tools).

## Current behavior

- Horizontal **Y reference lines** (`y_reference_lines[]`: `y`, optional `label`, optional `role`) on `line`,
  `bar`, `scatter` and `boxplot` charts, at most 12 per chart; not on `histogram` or `heatmap`. On a horizontal
  bar they lie on the value axis and are drawn vertically.
- **Roles** follow SPC language: `usl` / `lsl` (specification), `ucl` / `lcl` (control), `target` (center),
  `limit` (generic ceiling or floor, the default), `warning` (advisory). The reference client styles them by
  role: specification and limit lines red solid, control lines orange dashed, target green dashed, warning
  yellow dashed.
- The **Y domain** includes the reference values, so limits stay visible when every sample lies on one side.
- Reference lines are accepted by `query_property_history` (`y_reference_lines`),
  `build_chart_from_tabular_result` and `build_history_overlay_chart` (`yReferenceLines`). The model adds them
  when the user states limits; charts stay server-authored.

When chart or wire behavior changes, update the affected `CONTRACTS/*.md`, `CONTRACT_VERSION.md`, and the
`parler-ui` and `parler-agent` implementation and tests together. Styling changes also update the theme
registry, its tests and [`theme-api.md`](theme-api.md).
