/**
 * Row-level Copy / Print helpers for `<parler-ui>` (`docs/ui/assistant-response-actions.md`).
 * Clipboard IO stays in `parler-ui.js`; this module stays pure / DOM-testable where possible.
 */

/** @typedef {import('./types.js').ChatRow} ChatRow */
/** @typedef {import('./types.js').ChatUiState} ChatUiState */

import { THEME_TOKENS } from "./themeTokens.mjs";
import { DEFAULT_CHART_RENDER_THEME, resolveChartTheme } from "../components/chart-theme.js";
import { heatPaint } from "./chartHeatColor.js";

export const PRINT_USER_PROMPT_MAX_CHARS = 400;
export const COPY_FEEDBACK_MS = 1500;

const THEME_TOKEN_BY_PUBLIC_NAME = new Map(
  THEME_TOKENS.map((entry) => [entry.publicName, entry])
);
const PRINT_TOKENS = new Map(
  THEME_TOKENS
    .filter((entry) => entry.publicName.startsWith("--parler-print-"))
    .map((entry) => [entry.publicName.slice("--parler-".length), entry])
);

function tokenFallback(publicName) {
  const fallback = THEME_TOKEN_BY_PUBLIC_NAME.get(publicName)?.fallback;
  if (!fallback) throw new Error(`Unknown Theme token fallback: ${publicName}`);
  return fallback;
}

const BODY_TOKEN_FALLBACKS = Object.freeze({
  fontFamily: tokenFallback("--parler-font-family"),
  fontSize: tokenFallback("--parler-font-size"),
  lineHeight: tokenFallback("--parler-line-height"),
  imageRadius: tokenFallback("--parler-markdown-image-radius"),
});

function readStyle(style, name) {
  return String(style?.getPropertyValue?.(name) ?? "").trim();
}

function safeCssValue(value) {
  const text = String(value).trim();
  if (!text || /[{};]/.test(text) || /var\s*\(/i.test(text)) return null;
  if (/^(inherit|initial|unset|revert|revert-layer)$/i.test(text)) return null;
  return text;
}

function colorCandidate(value) {
  const text = safeCssValue(value);
  if (!text || /gradient\s*\(|url\s*\(/i.test(text)) return null;
  return text;
}

function defaultColorValidator(value) {
  const text = colorCandidate(value);
  if (!text) return null;
  return /^(?:#[0-9a-f]{3,8}|[a-z]+|(?:rgb|hsl|hwb|lab|lch|oklab|oklch|color)\([^{};]+\))$/i.test(text)
    ? text
    : null;
}

function positiveLength(value) {
  const text = safeCssValue(value);
  if (!text) return null;
  const match = /^(\d*\.?\d+)(px|em|rem|pt|pc|in|cm|mm|q|%)$/i.exec(text);
  return match && Number(match[1]) > 0 ? text : null;
}

function nonNegativeLength(value) {
  const text = safeCssValue(value);
  if (!text) return null;
  if (text === "0") return text;
  const match = /^(\d*\.?\d+)(px|em|rem|pt|pc|in|cm|mm|q|%)$/i.exec(text);
  return match ? text : null;
}

function positiveLineHeight(value) {
  const text = safeCssValue(value);
  if (!text) return null;
  if (/^\d*\.?\d+$/.test(text)) return Number(text) > 0 ? text : null;
  return positiveLength(text);
}

function firstValid(values, validate, label) {
  for (const value of values) {
    const parsed = validate(value);
    if (parsed != null) return parsed;
  }
  throw new Error(`Portable print Theme has no valid fallback for ${label}`);
}

function printValue(style, suffix, validate) {
  const entry = PRINT_TOKENS.get(suffix);
  if (!entry) throw new Error(`Unknown portable print token: ${suffix}`);
  return firstValid(
    [readStyle(style, entry.effectiveName), readStyle(style, entry.publicName), entry.fallback],
    validate,
    suffix
  );
}

/**
 * Resolve the portable print palette and typography from the live Parler host.
 * Print colors deliberately have no platform Theme candidates.
 *
 * @param {{ getPropertyValue?: (name: string) => string }} style
 * @param {{ validateColor?: (value: string) => string | null, validateFontFamily?: (value: string) => string | null, validateFontSize?: (value: string) => string | null, validateLineHeight?: (value: string) => string | null, validateImageRadius?: (value: string) => string | null }} [options]
 */
export function resolvePortablePrintTheme(style, options = {}) {
  const validateColor = options.validateColor ?? defaultColorValidator;
  const typographyValue = (publicName, fallback, validate) =>
    firstValid([readStyle(style, publicName), fallback], validate, publicName);

  return {
    chartType: chartTypographyForPrint(options.chartTheme ?? DEFAULT_CHART_RENDER_THEME),
    palette: {
      canvas: printValue(style, "print-canvas", validateColor),
      text: printValue(style, "print-text", validateColor),
      muted: printValue(style, "print-muted", validateColor),
      border: printValue(style, "print-border", validateColor),
      surfaceSubtle: printValue(style, "print-surface-subtle", validateColor),
      accent: printValue(style, "print-accent", validateColor),
      series: Array.from({ length: 24 }, (_, index) =>
        printValue(style, `print-chart-series-${index + 1}`, validateColor)
      ),
      outline: printValue(style, "print-chart-outline", validateColor),
      reference: {
        danger: printValue(style, "print-chart-reference-danger", validateColor),
        warning: printValue(style, "print-chart-reference-warning", validateColor),
        target: printValue(style, "print-chart-reference-target", validateColor),
      },
    },
    typography: {
      fontFamily: typographyValue(
        "--parler-font-family",
        BODY_TOKEN_FALLBACKS.fontFamily,
        options.validateFontFamily ?? safeCssValue
      ),
      fontSize: typographyValue(
        "--parler-font-size",
        BODY_TOKEN_FALLBACKS.fontSize,
        options.validateFontSize ?? positiveLength
      ),
      lineHeight: typographyValue(
        "--parler-line-height",
        BODY_TOKEN_FALLBACKS.lineHeight,
        options.validateLineHeight ?? positiveLineHeight
      ),
      imageRadius: typographyValue(
        "--parler-markdown-image-radius",
        BODY_TOKEN_FALLBACKS.imageRadius,
        options.validateImageRadius ?? nonNegativeLength
      ),
    },
  };
}

/**
 * Chart card typography carried into the print document: the validated, clamped values the
 * screen chart Theme resolved (font family, title size/weight, legend size), so the printed
 * card title and legend keep the screen chart type while colors come from the print palette.
 * @param {typeof DEFAULT_CHART_RENDER_THEME} chartTheme
 */
function chartTypographyForPrint(chartTheme) {
  const { style } = chartTheme;
  return {
    fontFamily: style.fontFamily,
    titleSize: style.type.titleSize,
    titleWeight: style.type.titleWeight,
    legendSize: style.type.legendSize,
  };
}

/** @param {Element} host */
export function resolvePortablePrintThemeFromElement(host) {
  const view = host?.ownerDocument?.defaultView;
  if (!view?.getComputedStyle) {
    return resolvePortablePrintTheme({ getPropertyValue: () => "" });
  }
  const chartTheme = resolveChartTheme(host);
  const probe = host.ownerDocument.createElement("span");
  const validateColor = (value) => {
    const text = colorCandidate(value);
    if (!text) return null;
    probe.style.color = "";
    probe.style.color = text;
    return probe.style.color ? text : null;
  };
  const validateProperty = (propertyName) => (value) => {
    const text = safeCssValue(value);
    if (!text) return null;
    probe.style.setProperty(propertyName, "");
    probe.style.setProperty(propertyName, text);
    return probe.style.getPropertyValue(propertyName) ? text : null;
  };
  return resolvePortablePrintTheme(view.getComputedStyle(host), {
    chartTheme,
    validateColor,
    validateFontFamily: validateProperty("font-family"),
    validateFontSize: validateProperty("font-size"),
    validateLineHeight: validateProperty("line-height"),
    validateImageRadius: validateProperty("border-radius"),
  });
}

/**
 * Recolor palette-owned attributes in a cloned assistant subtree.
 * Screen SVGs are never mutated.
 *
 * @param {HTMLElement} root
 * @param {ReturnType<typeof resolvePortablePrintTheme>} theme
 */
export function rewriteClonedChartPaletteForPrint(root, theme) {
  if (!root || typeof root.querySelectorAll !== "function") return 0;
  const knownRoles = new Set([
    "series", "point-outline", "title", "legend", "tick", "axis-label",
    "axis", "grid", "reference-danger", "reference-warning", "reference-target", "heat",
  ]);
  let rewritten = 0;
  for (const node of root.querySelectorAll("[data-parler-palette-role]")) {
    const roles = String(node.getAttribute("data-parler-palette-role") ?? "")
      .trim()
      .split(/\s+/)
      .filter(Boolean);
    if (!roles.length || roles.some((role) => !knownRoles.has(role))) {
      throw new Error(`Unknown print chart palette role: ${roles.join(" ") || "<empty>"}`);
    }

    if (roles.includes("heat")) {
      // Heatmap cells and colour-bar stops carry their normalised position; the fill is recomputed
      // from the print palette with the same rule the screen used (design §7.4).
      const t = Number(node.getAttribute("data-heat-t"));
      const kind = node.getAttribute("data-heat-scale");
      if (!Number.isFinite(t) || !["sequential", "diverging", "constant"].includes(kind ?? "")) {
        throw new Error(`Invalid print heat cell position: ${node.getAttribute("data-heat-t")} / ${kind}`);
      }
      const paint = heatPaint(t, /** @type {"sequential" | "diverging" | "constant"} */ (kind), theme.palette);
      if (node.hasAttribute("stop-color")) {
        node.setAttribute("stop-color", paint.fill);
        node.setAttribute("stop-opacity", String(paint.opacity));
      } else {
        node.setAttribute("fill", paint.fill);
        node.setAttribute("fill-opacity", String(paint.opacity));
      }
      rewritten += 1;
      continue;
    }
    if (roles.includes("series")) {
      const slot = Number(node.getAttribute("data-parler-series-slot"));
      if (!Number.isInteger(slot) || slot < 0 || slot >= theme.palette.series.length) {
        throw new Error(`Invalid print chart series slot: ${node.getAttribute("data-parler-series-slot")}`);
      }
      const color = theme.palette.series[slot];
      if (node.hasAttribute("fill") && node.getAttribute("fill") !== "none") {
        node.setAttribute("fill", color);
      }
      if (node.hasAttribute("stroke") && !roles.includes("point-outline")) {
        node.setAttribute("stroke", color);
      }
    }
    if (roles.includes("point-outline") && node.hasAttribute("stroke")) {
      node.setAttribute("stroke", theme.palette.outline);
    }

    const singleRoleColors = {
      title: theme.palette.text,
      legend: theme.palette.muted,
      tick: theme.palette.muted,
      "axis-label": theme.palette.muted,
      axis: theme.palette.border,
      grid: theme.palette.border,
      "reference-danger": theme.palette.reference.danger,
      "reference-warning": theme.palette.reference.warning,
      "reference-target": theme.palette.reference.target,
    };
    for (const role of roles) {
      const color = singleRoleColors[role];
      if (!color) continue;
      if (node.hasAttribute("fill") && node.getAttribute("fill") !== "none") {
        node.setAttribute("fill", color);
      }
      if (node.hasAttribute("stroke") && node.getAttribute("stroke") !== "none") {
        node.setAttribute("stroke", color);
      }
    }
    rewritten += 1;
  }
  return rewritten;
}

/** @param {ReturnType<typeof resolvePortablePrintTheme>} theme */
export function buildPortablePrintCss(theme) {
  const { palette, typography } = theme;
  const chartType = theme.chartType ?? chartTypographyForPrint(DEFAULT_CHART_RENDER_THEME);
  return `
html,
body {
  margin: 0;
  color: ${palette.text};
  background: ${palette.canvas};
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}
.print-root {
  max-width: 760px;
  margin: 24px auto;
  padding: 0 24px 24px;
  font-family: ${typography.fontFamily};
  font-size: ${typography.fontSize};
  line-height: ${typography.lineHeight};
  color: ${palette.text};
  background: ${palette.canvas};
}
.print-root h1,
.print-root h2,
.print-root h3,
.print-root h4,
.print-root h5,
.print-root h6 {
  color: ${palette.text};
}
.print-root a {
  color: ${palette.accent};
}
.print-user-prompt {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid ${palette.border};
}
.print-user-prompt-label,
.print-meta {
  color: ${palette.muted};
}
.print-user-prompt-body,
.print-root pre,
.print-root code,
.print-root blockquote {
  color: ${palette.text};
  background: ${palette.surfaceSubtle};
}
.print-user-prompt-body,
.print-root pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.print-root :not(pre) > code {
  padding: 1px 3px;
  border: 1px solid ${palette.border};
}
.print-root pre,
.print-root blockquote {
  padding: 8px 12px;
  border: 1px solid ${palette.border};
}
.print-root hr {
  border: 0;
  border-top: 1px solid ${palette.border};
}
parler-ui-chart,
.chart-root {
  display: block;
  width: 100%;
}
parler-ui-chart .chart-root svg,
.chart-root svg {
  width: 100%;
  height: auto;
}
.chart-card {
  display: block;
  width: 100%;
  margin: 12px 0;
  padding: 0;
  break-inside: avoid;
}
.chart-card-title {
  margin: 0 0 4px;
  overflow-wrap: anywhere;
  color: ${palette.text};
  font-family: ${chartType.fontFamily};
  font-size: ${chartType.titleSize}px;
  font-weight: ${chartType.titleWeight};
  line-height: 1.3;
}
.chart-heat-legend { display: flex; justify-content: center; margin: 6px 0 0; }
.chart-group { margin: 10px 0; padding: 8px 10px 6px; border: 1px solid ${palette.border}; border-radius: 6px; page-break-inside: avoid; }
.chart-group-title { margin: 0 0 6px; color: ${palette.text}; }
.chart-group-note, .chart-group-summary { margin: 6px 0 0; color: ${palette.muted}; }
.chart-heat-legend svg { width: 320px; max-width: 100%; height: auto; }
.chart-plot-area .chart-root { max-width: none; }
.chart-legend {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  max-width: 100%;
  color: ${palette.muted};
  font-family: ${chartType.fontFamily};
  font-size: ${chartType.legendSize}px;
  line-height: 1.3;
}
.chart-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}
.chart-legend-swatch {
  flex: none;
}
.chart-legend-label {
  min-width: 0;
  overflow-wrap: anywhere;
}
.chart-legend-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  min-width: 0;
  max-width: 100%;
}
.chart-legend-item[data-hidden] {
  opacity: 0.55;
  text-decoration: line-through;
}
.chart-card-meta,
.chart-view-note,
.chart-print-note {
  margin: 0 0 4px;
  color: ${palette.muted};
  font-family: ${chartType.fontFamily};
  font-size: ${chartType.legendSize}px;
  overflow-wrap: anywhere;
}
.chart-view-note,
.chart-print-note {
  margin: 4px 0 0;
}
.chart-actions,
.chart-data,
.chart-range,
.chart-expand-placeholder {
  display: none;
}
.chart-plot-area {
  max-height: none;
  overflow: visible;
}
.chart-root {
  margin: 0 auto;
}
.chart-card[data-legend-placement="aside"] {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: center;
  column-gap: 24px;
}
.chart-card[data-legend-placement="aside"] > * {
  flex: 1 1 100%;
  min-width: 0;
}
.chart-card[data-legend-placement="aside"] > .chart-plot-area {
  flex: 0 0 auto;
  width: auto;
}
.chart-card[data-legend-placement="aside"] > .chart-legend {
  flex: 0 1 var(--parler-chart-legend-width, 320px);
  max-width: var(--parler-chart-legend-width, 320px);
  flex-direction: column;
  gap: 6px;
  margin: 0;
  align-self: center;
}
.chart-card[data-legend-placement="aside"] .chart-legend-item {
  display: flex;
  width: 100%;
}
.chart-legend-value {
  margin-left: auto;
  padding-left: 12px;
  white-space: nowrap;
}
.chart-notes {
  margin: 6px 0 0;
  color: ${palette.text};
  font-family: ${chartType.fontFamily};
  font-size: ${chartType.legendSize}px;
  break-inside: avoid;
}
.chart-notes summary {
  color: ${palette.muted};
  list-style: none;
}
.chart-notes-list {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 2px 12px;
  margin: 4px 0 0;
}
.chart-notes-list dt {
  color: ${palette.muted};
}
.chart-notes-list dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.print-root img {
  max-width: 100%;
  height: auto;
  border-radius: ${typography.imageRadius};
}
parler-ui-chart,
.chart-root,
.parler-data-table,
.print-root table {
  page-break-inside: avoid;
  break-inside: avoid;
}
.print-root table,
.parler-data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
  border: 1px solid ${palette.border};
}
.print-root th,
.print-root td,
.parler-data-table th,
.parler-data-table td {
  border: 1px solid ${palette.border};
  padding: 4px 8px;
  text-align: left;
  vertical-align: top;
}
.print-root th,
.parler-data-table th {
  font-weight: 600;
  color: ${palette.text};
  background: ${palette.surfaceSubtle};
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}
@media print {
  .parler-data-table-dl a::after {
    content: " (" attr(href) ")";
  }
}
`.trim();
}

export const DEFAULT_PORTABLE_PRINT_THEME = resolvePortablePrintTheme({
  getPropertyValue: () => "",
});

/** Portable-default CSS retained as the deterministic fallback and test fixture. */
export const MINIMUM_PRINT_CSS = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);

/**
 * @param {string} s
 * @returns {string}
 */
export function escapeHtmlText(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/**
 * @param {ChatUiState} chatState
 * @param {ChatRow} row
 * @returns {boolean}
 */
export function isAssistantRowHitlPending(chatState, row) {
  if (row.kind !== "assistant") return false;
  const gate = chatState?.approvalGate;
  if (!gate || !gate.requestId || !row.requestId) return false;
  return gate.requestId === row.requestId;
}

/**
 * @param {ChatUiState} chatState
 * @param {ChatRow} row
 * @returns {boolean}
 */
export function isAssistantRowActiveStreaming(chatState, row) {
  if (row.kind !== "assistant") return false;
  return !!(chatState?.busy && chatState?.activeRequestId === row.requestId);
}

/**
 * @param {ChatRow} row
 * @param {ChatUiState} chatState
 * @returns {{ copy: boolean, print: boolean, showFooter: boolean, feedback: boolean, cutoff: boolean, info: boolean }}
 */
export function assistantRowActionsEligibility(row, chatState) {
  if (row.kind !== "assistant") {
    return { copy: false, print: false, showFooter: false, feedback: false, cutoff: false, info: false };
  }
  if (isAssistantRowHitlPending(chatState, row) || isAssistantRowActiveStreaming(chatState, row)) {
    return { copy: false, print: false, showFooter: false, feedback: false, cutoff: false, info: false };
  }
  const md = typeof row.markdown === "string" ? row.markdown.trim() : "";
  const hasCharts = Array.isArray(row.charts) && row.charts.length > 0;
  const hasTables = Array.isArray(row.tables) && row.tables.length > 0;
  const copy = md.length > 0;
  const print = md.length > 0 || hasCharts || hasTables;
  const aid =
    typeof row.assistantMessageId === "string" && row.assistantMessageId.trim()
      ? row.assistantMessageId.trim()
      : "";
  const feedback = !!aid;
  const info = !!aid;
  const cutoff =
    !!(typeof row.completedAt === "string" && row.completedAt.trim() !== "");
  const showFooter = copy || print || feedback || cutoff || info;
  return { copy, print, showFooter, feedback, cutoff, info };
}

/**
 * @param {ChatRow[]} rows
 * @param {number} assistantRowIndex
 * @param {number} [maxChars]
 * @returns {string | null}
 */
export function findPriorUserPromptText(rows, assistantRowIndex, maxChars = PRINT_USER_PROMPT_MAX_CHARS) {
  if (!Array.isArray(rows) || assistantRowIndex <= 0) return null;
  for (let i = assistantRowIndex - 1; i >= 0; i--) {
    const r = rows[i];
    if (r && r.kind === "user" && typeof r.text === "string") {
      const t = r.text.trim();
      if (t.length > 0) {
        return t.length <= maxChars ? t : `${t.slice(0, maxChars)}…`;
      }
    }
  }
  return null;
}

/**
 * Mutates `root` (cloned assistant bubble subtree).
 * @param {HTMLElement} root
 */
export function stripNonPrintableControls(root) {
  if (!root || typeof root.querySelectorAll !== "function") return;
  const removeList = (sel) => {
    root.querySelectorAll(sel).forEach((el) => {
      try {
        el.remove();
      } catch {
        /* ignore */
      }
    });
  };
  removeList("[data-no-print]");
  removeList("aside.task-state-panel");
  removeList(".working");
}

/**
 * Replace every chart card in the print clone with a card built from its artifact, and insert
 * cards for chart artifacts that have no element in the clone (expanded, not yet mounted or
 * removed), so printed chart cards always equal the answer's chart artifacts in count and order
 * (chart-enhancement design §4.5 C1b-1, §10.2). Membership comes from `artifacts`, never from
 * the screen DOM. Table artifacts keep the existing clone path.
 *
 * @param {HTMLElement} clone cloned assistant bubble, already stripped of non-printable controls
 * @param {{ type: string; key: string; chart?: object }[]} artifacts answer artifacts in display order
 * @param {(artifact: { type: string; key: string; chart?: object }) => HTMLElement} buildCard
 * @returns {number} number of chart cards in the clone after assembly
 */
export function assembleChartCardsForPrint(clone, artifacts, buildCard) {
  if (!clone || typeof clone.querySelectorAll !== "function" || !Array.isArray(artifacts)) return 0;
  const byKey = (selector, key) =>
    [...clone.querySelectorAll(selector)].find((el) => el.getAttribute("data-parler-artifact-key") === key) ?? null;
  const elementFor = (artifact) =>
    artifact.type === "table"
      ? byKey(".parler-data-table-wrap[data-parler-artifact-key]", artifact.key)
      : byKey("parler-ui-chart[data-parler-artifact-key], figure.chart-card[data-parler-artifact-key]", artifact.key);
  let count = 0;
  artifacts.forEach((artifact, index) => {
    if (!artifact || artifact.type !== "chart" || !artifact.chart) return;
    const card = buildCard(artifact);
    card.setAttribute("data-parler-artifact-key", artifact.key);
    const existing = byKey("parler-ui-chart[data-parler-artifact-key]", artifact.key);
    if (existing) {
      existing.replaceWith(card);
    } else {
      let anchor = null;
      for (let j = index + 1; j < artifacts.length && !anchor; j++) anchor = elementFor(artifacts[j]);
      if (anchor) {
        anchor.before(card);
      } else {
        let previous = null;
        for (let j = index - 1; j >= 0 && !previous; j--) previous = elementFor(artifacts[j]);
        if (previous) previous.after(card);
        else {
          const markdown = clone.querySelector(".md");
          if (markdown) markdown.before(card);
          else clone.append(card);
        }
      }
    }
    count += 1;
  });
  return count;
}

/**
 * Open every chart card's data-notes disclosure in the print clone so the notes print readable
 * (chart-enhancement design §10.2); the screen DOM is untouched.
 * @param {HTMLElement} root
 */
export function expandChartNotesForPrint(root) {
  if (!root || typeof root.querySelectorAll !== "function") return;
  root.querySelectorAll("details.chart-notes, details.chart-notes-diagnostics").forEach((el) => {
    el.setAttribute("open", "");
  });
}

/**
 * @param {string} bodyInnerHtml trusted HTML from clone (same origin DOM)
 * @param {{ userPromptText?: string | null, requestId?: string, conversationId?: string, printTheme?: ReturnType<typeof resolvePortablePrintTheme> }} opts
 * @returns {string}
 */
export function buildPrintDocumentHtml(bodyInnerHtml, opts = {}) {
  const userPromptText = opts.userPromptText && String(opts.userPromptText).trim() ? opts.userPromptText : null;
  const requestId = opts.requestId ? String(opts.requestId) : "";
  const conversationId = opts.conversationId ? String(opts.conversationId) : "";

  const parts = [];
  parts.push(
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Parler print</title><style>",
    opts.printTheme ? buildPortablePrintCss(opts.printTheme) : MINIMUM_PRINT_CSS,
    "</style></head><body><div class=\"print-root\">"
  );
  if (userPromptText) {
    parts.push(
      '<header class="print-user-prompt"><div class="print-user-prompt-label">User asked</div>',
      '<pre class="print-user-prompt-body">',
      escapeHtmlText(userPromptText),
      "</pre></header>"
    );
  }
  parts.push('<main class="print-body">', bodyInnerHtml, "</main>");
  if (requestId || conversationId) {
    parts.push('<footer class="print-meta">');
    if (requestId) {
      parts.push("<div>request: ", escapeHtmlText(requestId), "</div>");
    }
    if (conversationId) {
      parts.push("<div>conversation: ", escapeHtmlText(conversationId), "</div>");
    }
    parts.push("</footer>");
  }
  parts.push("</div></body></html>");
  return parts.join("");
}

/**
 * @param {string} markdownPlain
 * @param {string} renderedHtmlFromMarkdownIt
 * @returns {{ html: string, plain: string }}
 */
export function buildCopyClipboardParts(markdownPlain, renderedHtmlFromMarkdownIt) {
  const plain = markdownPlain == null ? "" : String(markdownPlain);
  const inner = renderedHtmlFromMarkdownIt == null ? "" : String(renderedHtmlFromMarkdownIt);
  const html = `<article class="parler-copy-html">${inner}</article>`;
  return { html, plain };
}

/**
 * @param {Window} w
 * @returns {Promise<void>}
 */
export function waitForPrintDocument(w) {
  if (!w || !w.document) return Promise.resolve();
  const doc = w.document;
  const go = () =>
    new Promise((resolve) => {
      w.requestAnimationFrame(() => {
        w.focus();
        w.print();
        resolve();
      });
    });
  if (doc.fonts && doc.fonts.ready) {
    return doc.fonts.ready.then(() => go()).catch(() => go());
  }
  return new Promise((resolve) => {
    const finish = () => go().then(resolve);
    if (doc.readyState === "complete") finish();
    else w.addEventListener("load", finish, { once: true });
  });
}
