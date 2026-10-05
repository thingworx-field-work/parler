/**
 * Implemented Theme token registry, expanded through reviewed vertical slices.
 *
 * Public names are the supported application API. Theme-source and effective
 * names are internal to Parler. A null expression means the role has no
 * ThingWorx Style Theme source and resolves from a public override or fallback.
 */
export const THEME_TOKENS = Object.freeze([
  token(
    "font-family",
    '"Open Sans", "Segoe UI", system-ui, sans-serif',
    "global-text-body-fontfamily",
    "CSS font-family list"
  ),
  token("font-size", "14px", "global-text-body-fontsize", "Positive CSS length"),
  token(
    "line-height",
    "1.5",
    null,
    "Positive unitless number or CSS length"
  ),
  token("color-canvas", "#ffffff", "global-color-bg-primary", "CSS color"),
  token("color-surface", "#f7f7f7", "global-color-bg-secondary", "CSS color"),
  token(
    "color-surface-raised",
    "#ffffff",
    "global-color-bg-primary",
    "CSS color"
  ),
  token(
    "color-surface-subtle",
    "#f2f4f5",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token("color-text", "#232b2d", "global-color-text-body", "CSS color"),
  token("color-text-muted", "#5f6b70", "global-color-text-label", "CSS color"),
  token("color-border", "#c2c7ce", "global-color-line-border", "CSS color"),
  token("color-accent", "#006f9b", "global-color-core-primary", "CSS color"),
  token("color-on-accent", "#ffffff", "e-button-primary-fontcolor", "CSS color"),
  token("color-focus", "#006f9b", "global-focus-border-color", "CSS color"),
  token("color-danger", "#af3231", "global-color-core-danger", "CSS color"),
  token("color-warning", "#8a5a00", null, "CSS color"),
  token("color-success", "#13783a", "global-color-core-success", "CSS color"),
  token("color-scrim", "rgba(0, 0, 0, 0.32)", null, "CSS color with optional alpha"),
  token("color-control-bg", "#ffffff", "global-color-bg-primary", "CSS color"),
  token("color-control-text", "#232b2d", "global-color-text-body", "CSS color"),
  token("color-control-border", "#c2c7ce", "global-color-line-border", "CSS color"),
  token(
    "color-control-hover",
    "#eef4f7",
    "global-color-bg-hover",
    "CSS color"
  ),
  token(
    "color-control-disabled",
    "#e5e7e9",
    "global-color-bg-disabled",
    "CSS color"
  ),
  token("color-placeholder", "#5f6b70", "global-color-text-label", "CSS color"),
  token(
    "color-scrollbar-thumb",
    "#c2c7ce",
    "global-color-line-border",
    "CSS color"
  ),
  token(
    "color-scrollbar-track",
    "#f7f7f7",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "color-selection-bg",
    "#006f9b",
    "global-color-core-primary",
    "CSS color"
  ),
  token(
    "color-selection-text",
    "#ffffff",
    "e-button-primary-fontcolor",
    "CSS color"
  ),
  token(
    "color-user-message",
    "#f2f4f5",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "color-on-user-message",
    "#232b2d",
    "global-color-text-body",
    "CSS color"
  ),
  token(
    "color-assistant-message",
    "#f7f7f7",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "color-on-assistant-message",
    "#232b2d",
    "global-color-text-body",
    "CSS color"
  ),
  token(
    "content-max-width",
    "1200px",
    null,
    "Positive CSS length or percentage"
  ),
  token(
    "message-max-width",
    "min(92%, 960px)",
    null,
    "Positive CSS length, percentage, or valid CSS comparison function"
  ),
  token(
    "activity-max-width",
    "100%",
    null,
    "Positive CSS length or percentage; clamped to its container"
  ),
  token(
    "empty-state-media-max-width",
    "320px",
    null,
    "Positive CSS length or percentage; clamped to the empty-state width"
  ),
  token(
    "empty-state-media-max-height",
    "160px",
    null,
    "Positive CSS length or percentage; clamped to the empty-state height"
  ),
  token(
    "empty-state-media-opacity",
    "1",
    null,
    "Number from `0` to `1`"
  ),
  token(
    "empty-state-media-filter",
    "none",
    null,
    "`none` or a valid CSS filter-function list"
  ),
  token(
    "radius-panel",
    "12px",
    "global-border-radius-300",
    "Non-negative CSS length"
  ),
  token(
    "radius-message",
    "12px",
    "global-border-radius-200",
    "Non-negative CSS length"
  ),
  token(
    "radius-control",
    "8px",
    "global-border-radius-200",
    "Non-negative CSS length"
  ),
  token(
    "shadow-panel",
    "0 4px 20px rgba(0, 0, 0, 0.18)",
    null,
    "CSS box-shadow value"
  ),
  token("space-unit", "4px", null, "Non-negative CSS length"),
  token("markdown-heading", "#232b2d", "global-color-text-header", "CSS color"),
  token("markdown-link", "#006f9b", "global-color-core-primary", "CSS color"),
  token("markdown-link-visited", "#6f42c1", null, "CSS color"),
  token(
    "markdown-code-bg",
    "#f2f4f5",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "markdown-code-text",
    "#232b2d",
    "global-color-text-body",
    "CSS color"
  ),
  token(
    "markdown-code-border",
    "#d8dbde",
    "global-color-line-border",
    "CSS color"
  ),
  token(
    "markdown-quote-bg",
    "#f2f4f5",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "markdown-quote-text",
    "#232b2d",
    "global-color-text-body",
    "CSS color"
  ),
  token(
    "markdown-quote-border",
    "#c2c7ce",
    "global-color-line-border",
    "CSS color"
  ),
  token(
    "markdown-table-header-bg",
    "#f2f4f5",
    "global-color-bg-secondary",
    "CSS color"
  ),
  token(
    "markdown-table-border",
    "#c2c7ce",
    "global-color-line-border",
    "CSS color"
  ),
  token("markdown-hr", "#d8dbde", "global-color-line-divider", "CSS color"),
  token(
    "markdown-image-radius",
    "8px",
    "global-border-radius-200",
    "Non-negative CSS length"
  ),
  ...[
    "#3363d8",
    "#139492",
    "#8a4ff7",
    "#eb6d00",
    "#2f97ff",
    "#f05b80",
    "#2a5b59",
    "#aa9103",
    "#2b387f",
    "#b8849a",
    "#8d909a",
    "#bb68c8",
    "#c33e70",
    "#904b6f",
    "#9d27b0",
    "#00709e",
    "#381394",
    "#8f3809",
    "#8e6514",
    "#5d5f69",
    "#86335e",
    "#490e3e",
    "#56226e",
    "#1d496e",
  ].map((fallback, index) =>
    token(
      `chart-series-${index + 1}`,
      fallback,
      `e-chart-series-${index + 1}-color`,
      "Solid CSS color"
    )
  ),
  token("chart-title-text", "#232b2d", "global-color-text-header", "Solid CSS color"),
  token("chart-legend-text", "#5f6b70", "global-color-text-label", "Solid CSS color"),
  token("chart-tick-text", "#5f6b70", "e-chart-tick-label-color", "Solid CSS color"),
  token("chart-axis-label-text", "#5f6b70", "global-color-text-label", "Solid CSS color"),
  token("chart-axis", "#6b7377", "e-chart-ruler-color", "Solid CSS color"),
  token("chart-grid", "#d8dbde", "global-color-line-divider", "Solid CSS color"),
  token("chart-point-outline", "#ffffff", "e-chart-bar-outline-color", "Solid CSS color"),
  token("chart-reference-danger", "#af3231", "global-color-core-danger", "Solid CSS color"),
  token("chart-reference-warning", "#8a5a00", null, "Solid CSS color"),
  token("chart-reference-target", "#6e717c", "e-chart-reference-line-color", "Solid CSS color"),
  token(
    "chart-font-family",
    '"Open Sans", "Segoe UI", system-ui, sans-serif',
    "global-text-body-fontfamily",
    "CSS font-family list"
  ),
  token("chart-line-width", "2px", null, "Positive CSS length, safely clamped"),
  token("chart-line-point-radius", "3px", null, "Non-negative CSS length, safely clamped"),
  token("chart-scatter-point-radius", "4px", null, "Positive CSS length, safely clamped"),
  token("chart-outline-width", "1px", null, "Non-negative CSS length, safely clamped"),
  token("chart-bar-radius", "3px", null, "Non-negative CSS length, safely clamped"),
  token("chart-axis-line-width", "1px", "global-line-border-thickness", "Positive CSS length, safely clamped"),
  token("chart-grid-line-width", "1px", null, "Positive CSS length, safely clamped"),
  token("chart-grid-opacity", "0.7", null, "Number from `0` to `1`"),
  token("chart-reference-line-width", "2px", null, "Positive CSS length, safely clamped"),
  token("chart-target-line-width", "1.5px", null, "Positive CSS length, safely clamped"),
  token("chart-reference-opacity", "0.95", null, "Number from `0` to `1`"),
  token("chart-reference-limit-dash", "none", null, "`none` or SVG dash-array number list"),
  token("chart-reference-control-dash", "6 4", null, "`none` or SVG dash-array number list"),
  token("chart-reference-target-dash", "4 3", null, "`none` or SVG dash-array number list"),
  token("chart-reference-warning-dash", "6 3", null, "`none` or SVG dash-array number list"),
  token("chart-title-font-size", "14px", null, "Positive CSS length, safely clamped"),
  token("chart-title-font-weight", "600", null, "CSS font-weight"),
  token("chart-legend-font-size", "12px", null, "Positive CSS length, safely clamped"),
  token("chart-tick-font-size", "12px", null, "Positive CSS length, safely clamped"),
  token("chart-axis-label-font-size", "11px", null, "Positive CSS length, safely clamped"),
  token("chart-reference-label-font-size", "10px", null, "Positive CSS length, safely clamped"),
  token("chart-legend-swatch-size", "10px", null, "Positive CSS length, safely clamped"),
  token("chart-legend-swatch-radius", "2px", null, "Non-negative CSS length, safely clamped"),
  token("chart-legend-line-length", "14px", null, "Positive CSS length, safely clamped"),
  token("chart-tick-length", "6px", null, "Non-negative CSS length, safely clamped"),
  token("chart-tick-padding", "3px", null, "Non-negative CSS length, safely clamped"),
  token("print-canvas", "#ffffff", null, "CSS color"),
  token("print-text", "#111111", null, "CSS color"),
  token("print-muted", "#555555", null, "CSS color"),
  token("print-border", "#888888", null, "CSS color"),
  token("print-surface-subtle", "#f0f0f0", null, "CSS color"),
  token("print-accent", "#005ea8", null, "CSS color"),
  ...[
    "#1b4f72",
    "#117864",
    "#7d3c98",
    "#a04000",
    "#1f618d",
    "#a93226",
    "#196f3d",
    "#7d6608",
    "#34495e",
    "#884ea0",
    "#5b2c6f",
    "#21618c",
    "#7b241c",
    "#0e6251",
    "#6e2c00",
    "#4a235a",
    "#1b2631",
    "#78281f",
    "#145a32",
    "#512e5f",
    "#154360",
    "#641e16",
    "#0b5345",
    "#4d5656",
  ].map((fallback, index) =>
    token(
      `print-chart-series-${index + 1}`,
      fallback,
      null,
      "Solid CSS color"
    )
  ),
  token("print-chart-outline", "#ffffff", null, "CSS color"),
  token("print-chart-reference-danger", "#a61b1b", null, "CSS color"),
  token("print-chart-reference-warning", "#8a5700", null, "CSS color"),
  token("print-chart-reference-target", "#126b3a", null, "CSS color"),
]);

/**
 * Platform sources for semantic state roles that intentionally have no public
 * token. Public-token overrides remain available through the owning part/state.
 */
export const THEME_ROLE_SOURCES = Object.freeze([
  Object.freeze({
    themeName: "--parler-theme-notice-info",
    expression: "global-color-core-info",
  }),
  Object.freeze({
    themeName: "--parler-theme-chart-point-outline-secondary",
    expression: "e-chart-pie-slice-stroke-color",
  }),
]);

/** Complete bridge manifest inventory: sourced public roles plus internal roles. */
export const THEME_SOURCES = Object.freeze([
  ...THEME_TOKENS.filter((entry) => entry.expression != null).map((entry) =>
    Object.freeze({
      themeName: entry.themeName,
      expression: entry.expression,
    })
  ),
  ...THEME_ROLE_SOURCES,
]);

/**
 * @param {string} suffix
 * @param {string} fallback
 * @param {string | null} expression
 * @param {string} acceptedValue
 */
function token(suffix, fallback, expression, acceptedValue) {
  return Object.freeze({
    publicName: `--parler-${suffix}`,
    themeName: expression == null ? null : `--parler-theme-${suffix}`,
    effectiveName: `--parler-effective-${suffix}`,
    expression,
    fallback,
    acceptedValue,
  });
}
