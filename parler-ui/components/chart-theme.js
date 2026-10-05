import { THEME_TOKENS } from "../lib/themeTokens.mjs";

const CHART_TOKENS = new Map(
  THEME_TOKENS
    .filter((entry) => entry.publicName.startsWith("--parler-chart-"))
    .map((entry) => [entry.publicName.slice("--parler-".length), entry])
);

const DARK_COLORS = Object.freeze({
  series: [
    "#8ab4ff", "#ffb74d", "#81c784", "#e57373", "#ba68c8", "#4dd0e1",
    "#ffd54f", "#90a4ae", "#ff8a65", "#9575cd", "#64b5f6", "#f48fb1",
    "#80cbc4", "#dce775", "#ce93d8", "#4fc3f7", "#b39ddb", "#ffab91",
    "#fff176", "#b0bec5", "#f06292", "#bcaaa4", "#7986cb", "#4db6ac",
  ],
  title: "#e8eaed",
  legend: "#9aa0a6",
  tick: "#9aa0a6",
  axisLabel: "#9aa0a6",
  axis: "#6b7377",
  grid: "#3d4652",
  pointOutline: "#0e1013",
  danger: "#f28b82",
  warning: "#ffa546",
  target: "#81c995",
});

/** Validated pixel ranges per length token; the card CSS clamps its defaults to the same values. */
export const CHART_LENGTH_LIMITS = Object.freeze({
  "chart-line-width": [0.25, 12],
  "chart-line-point-radius": [0, 16],
  "chart-scatter-point-radius": [0.5, 20],
  "chart-outline-width": [0, 8],
  "chart-bar-radius": [0, 24],
  "chart-axis-line-width": [0.25, 8],
  "chart-grid-line-width": [0.25, 8],
  "chart-reference-line-width": [0.25, 12],
  "chart-target-line-width": [0.25, 12],
  "chart-title-font-size": [8, 16],
  "chart-legend-font-size": [6, 18],
  "chart-tick-font-size": [6, 18],
  "chart-axis-label-font-size": [6, 14],
  "chart-reference-label-font-size": [6, 12],
  "chart-legend-swatch-size": [4, 12],
  "chart-legend-swatch-radius": [0, 6],
  "chart-legend-line-length": [6, 48],
  "chart-tick-length": [0, 16],
  "chart-tick-padding": [0, 16],
});

function read(style, name) {
  return String(style?.getPropertyValue?.(name) ?? "").trim();
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function fallbackFor(suffix, mode) {
  if (mode !== "parler-dark") return CHART_TOKENS.get(suffix)?.fallback ?? "";
  if (suffix.startsWith("chart-series-")) {
    const index = Number(suffix.slice("chart-series-".length)) - 1;
    return DARK_COLORS.series[index] ?? CHART_TOKENS.get(suffix)?.fallback ?? "";
  }
  const dark = {
    "chart-title-text": DARK_COLORS.title,
    "chart-legend-text": DARK_COLORS.legend,
    "chart-tick-text": DARK_COLORS.tick,
    "chart-axis-label-text": DARK_COLORS.axisLabel,
    "chart-axis": DARK_COLORS.axis,
    "chart-grid": DARK_COLORS.grid,
    "chart-point-outline": DARK_COLORS.pointOutline,
    "chart-reference-danger": DARK_COLORS.danger,
    "chart-reference-warning": DARK_COLORS.warning,
    "chart-reference-target": DARK_COLORS.target,
  };
  return dark[suffix] ?? CHART_TOKENS.get(suffix)?.fallback ?? "";
}

function candidates(style, suffix, mode) {
  const entry = CHART_TOKENS.get(suffix);
  const values = [read(style, entry?.effectiveName), read(style, entry?.publicName)];
  if (mode !== "parler-dark" && entry?.themeName) {
    values.push(read(style, entry.themeName));
    if (suffix === "chart-point-outline") {
      values.push(read(style, "--parler-theme-chart-point-outline-secondary"));
    }
  }
  values.push(fallbackFor(suffix, mode));
  return values;
}

function firstValid(style, suffix, mode, validate) {
  for (const value of candidates(style, suffix, mode)) {
    const parsed = validate(value);
    if (parsed !== null && parsed !== undefined) return parsed;
  }
  throw new Error(`Chart Theme has no valid fallback for ${suffix}`);
}

function fontFamily(value) {
  const text = String(value).trim();
  if (!text || /^(inherit|initial|unset|revert|revert-layer)$/i.test(text)) return null;
  if (/var\s*\(/i.test(text) || /[{};]/.test(text)) return null;
  return text;
}

function opacity(value) {
  const text = String(value).trim();
  if (!text) return null;
  const number = Number(text);
  return Number.isFinite(number) ? clamp(number, 0, 1) : null;
}

function fontWeight(value) {
  const text = String(value).trim().toLowerCase();
  if (!text) return null;
  if (["normal", "bold", "bolder", "lighter"].includes(text)) return text;
  const number = Number(text);
  return Number.isFinite(number) ? String(clamp(number, 1, 1000)) : null;
}

function dashPattern(value) {
  const text = String(value).trim().toLowerCase();
  if (text === "none") return "none";
  const pieces = text.split(/[\s,]+/).filter(Boolean);
  if (!pieces.length || pieces.length > 16) return null;
  const numbers = pieces.map(Number);
  if (numbers.some((number) => !Number.isFinite(number) || number < 0)) return null;
  if (!numbers.some((number) => number > 0)) return null;
  return numbers.map((number) => String(clamp(number, 0, 100))).join(" ");
}

function defaultColorValidator(value) {
  const text = String(value).trim();
  if (!text || /gradient\s*\(|url\s*\(|var\s*\(/i.test(text)) return null;
  if (/^(currentcolor|inherit|initial|unset|revert|revert-layer)$/i.test(text)) return null;
  return text;
}

function defaultLengthToPixels(value) {
  const match = /^(-?(?:\d+\.?\d*|\.\d+))(?:px)?$/i.exec(String(value).trim());
  return match ? Number(match[1]) : null;
}

/**
 * Resolve and validate a complete D3 render object from computed host tokens.
 * Browser callers provide validators that understand the active document;
 * tests may pass deterministic pure adapters.
 */
export function resolveChartRenderObject(style, options = {}) {
  const mode = options.mode === "parler-dark" ? "parler-dark" : "mashup";
  const validateColor = options.validateColor ?? defaultColorValidator;
  const lengthToPixels = options.lengthToPixels ?? defaultLengthToPixels;
  const color = (suffix) => firstValid(style, suffix, mode, validateColor);
  const length = (suffix) => {
    const [minimum, maximum] = CHART_LENGTH_LIMITS[suffix];
    return firstValid(style, suffix, mode, (value) => {
      const pixels = lengthToPixels(value);
      return Number.isFinite(pixels) ? clamp(pixels, minimum, maximum) : null;
    });
  };
  const value = (suffix, validate) => firstValid(style, suffix, mode, validate);

  return {
    palette: {
      series: Array.from({ length: 24 }, (_, index) => color(`chart-series-${index + 1}`)),
      text: {
        title: color("chart-title-text"),
        legend: color("chart-legend-text"),
        tick: color("chart-tick-text"),
        axisLabel: color("chart-axis-label-text"),
      },
      axis: color("chart-axis"),
      grid: color("chart-grid"),
      pointOutline: color("chart-point-outline"),
      reference: {
        danger: color("chart-reference-danger"),
        warning: color("chart-reference-warning"),
        target: color("chart-reference-target"),
      },
    },
    style: {
      fontFamily: value("chart-font-family", fontFamily),
      lineWidth: length("chart-line-width"),
      linePointRadius: length("chart-line-point-radius"),
      scatterPointRadius: length("chart-scatter-point-radius"),
      outlineWidth: length("chart-outline-width"),
      barRadius: length("chart-bar-radius"),
      axisLineWidth: length("chart-axis-line-width"),
      gridLineWidth: length("chart-grid-line-width"),
      gridOpacity: value("chart-grid-opacity", opacity),
      reference: {
        lineWidth: length("chart-reference-line-width"),
        targetLineWidth: length("chart-target-line-width"),
        opacity: value("chart-reference-opacity", opacity),
        limitDash: value("chart-reference-limit-dash", dashPattern),
        controlDash: value("chart-reference-control-dash", dashPattern),
        targetDash: value("chart-reference-target-dash", dashPattern),
        warningDash: value("chart-reference-warning-dash", dashPattern),
      },
      type: {
        titleSize: length("chart-title-font-size"),
        titleWeight: value("chart-title-font-weight", fontWeight),
        legendSize: length("chart-legend-font-size"),
        tickSize: length("chart-tick-font-size"),
        axisLabelSize: length("chart-axis-label-font-size"),
        referenceLabelSize: length("chart-reference-label-font-size"),
      },
      legend: {
        swatchSize: length("chart-legend-swatch-size"),
        swatchRadius: length("chart-legend-swatch-radius"),
        lineLength: length("chart-legend-line-length"),
      },
      axis: {
        tickLength: length("chart-tick-length"),
        tickPadding: length("chart-tick-padding"),
      },
    },
  };
}

function browserColorValidator(source) {
  const document = source.ownerDocument;
  const probe = document.createElement("span");
  return (value) => {
    const text = defaultColorValidator(value);
    if (!text) return null;
    probe.style.color = "";
    probe.style.color = text;
    return probe.style.color ? text : null;
  };
}

function browserLengthResolver(source) {
  const document = source.ownerDocument;
  const probe = document.createElement("span");
  probe.setAttribute("aria-hidden", "true");
  probe.style.cssText = "position:absolute;display:block;visibility:hidden;pointer-events:none;contain:strict;height:0;overflow:hidden;";
  source.append(probe);
  return {
    convert(value) {
      probe.style.width = "";
      probe.style.width = String(value).trim();
      if (!probe.style.width) return null;
      const pixels = Number.parseFloat(document.defaultView.getComputedStyle(probe).width);
      return Number.isFinite(pixels) ? pixels : defaultLengthToPixels(value);
    },
    remove() {
      probe.remove();
    },
  };
}

/** Resolve chart tokens from the closest Parler host, or the chart host standalone. */
export function resolveChartTheme(source) {
  const host = source.closest?.("parler-ui") ?? source;
  const view = host.ownerDocument?.defaultView ?? globalThis;
  const computed = view.getComputedStyle(host);
  const lengths = browserLengthResolver(host);
  try {
    return resolveChartRenderObject(computed, {
      mode: host.getAttribute?.("theme-mode"),
      validateColor: browserColorValidator(host),
      lengthToPixels: (value) => lengths.convert(value),
    });
  } finally {
    lengths.remove();
  }
}

export const DEFAULT_CHART_RENDER_THEME = Object.freeze(
  resolveChartRenderObject({ getPropertyValue: () => "" })
);

export function chartThemeSignature(theme) {
  return JSON.stringify(theme);
}

export function chartSeriesSlot(index) {
  const number = Number(index);
  return Number.isFinite(number) ? ((Math.trunc(number) % 24) + 24) % 24 : 0;
}

/**
 * C3b-2a (design §8.7): the category key of a label — trimmed, internal whitespace runs collapsed to one space,
 * case-sensitive; `null` when it folds to empty. The same rule the server uses to build a group's shared keys.
 * @param {unknown} text
 * @returns {string | null}
 */
export function normalizeCategoryKey(text) {
  if (text === null || text === undefined) return null;
  const key = String(text).trim().replace(/\s+/g, " ");
  return key ? key : null;
}

/**
 * C3b-2a (design §8.7): the palette slot of a category. With a group's shared `colorKeys`, a key found in the
 * list owns the slot equal to its index; otherwise, or without keys, the per-chart slot
 * `chartSeriesSlot(fallbackIndex)` — byte-identical to the behaviour before shared colours.
 * @param {unknown} categoryText @param {number} fallbackIndex @param {readonly string[] | null | undefined} colorKeys
 */
export function resolveSeriesSlot(categoryText, fallbackIndex, colorKeys) {
  if (Array.isArray(colorKeys) && colorKeys.length) {
    const key = normalizeCategoryKey(categoryText);
    if (key !== null) {
      const at = colorKeys.indexOf(key);
      if (at >= 0 && at < 24) return at;
    }
  }
  return chartSeriesSlot(fallbackIndex);
}
