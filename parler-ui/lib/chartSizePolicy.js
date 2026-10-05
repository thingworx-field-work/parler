/**
 * Plot size policy (chart-enhancement design §4.6, L1). The plot's logical size is a pure
 * function of `(kind, data counts, available width)` plus the rendering context; nothing from
 * the model, tools or wire takes part. The card, the expand layer and the print builder all call
 * it with the same inputs and get the same answer. 1 logical px = 1 CSS px stays true because
 * the caller gives the plot host exactly `w` CSS px and centres it inside the available width.
 */

export const PHI = 1.618;
export const PIE_MIN_SIDE = 200;
export const PIE_MAX_SIDE = 400;
/** Gap between a pie disc and its aside legend, and the legend column's width cap. */
export const PIE_LEGEND_GAP = 24;
export const PIE_LEGEND_MAX_WIDTH = 320;
/** The narrowest legend column worth placing beside the disc. */
export const PIE_LEGEND_MIN_WIDTH = 240;
export const DEFAULT_PLOT_HEIGHT = 240;
export const MIN_PLOT_WIDTH = 240;
/** Band charts: the widest category step, growing with the series slots, capped. */
export const BAND_STEP_BASE = 96;
export const BAND_STEP_PER_SERIES = 32;
export const BAND_STEP_MAX = 256;
/** Heatmap cells (§7.4): width clamp on screen, height ratio and clamp, print floors, scroll caps. */
export const HEAT_CELL_MIN_W = 20;
export const HEAT_CELL_MAX_W = 64;
export const HEAT_CELL_MIN_H = 20;
export const HEAT_CELL_MAX_H = 40;
export const HEAT_CELL_H_RATIO = 0.62;
export const HEAT_PRINT_MAX_WIDTH = 760;
export const HEAT_PRINT_CELL_MIN_W = 4;
export const HEAT_PRINT_CELL_MIN_H = 8;

/**
 * @typedef {"card" | "expand" | "print"} ChartSizeContext
 * @typedef {object} ChartPlotSizeInput
 * @property {string} kind `line` | `scatter` | `bar` | `pie` | `histogram` | `boxplot` | `heatmap`
 * @property {"vertical" | "horizontal"} [orientation] bar only
 * @property {number} [categories] band charts: category count
 * @property {number} [rows] heatmap: row count
 * @property {number} [cols] heatmap: column count
 * @property {number} [marginTop] heatmap: the renderer's top margin
 * @property {number} [marginBottom] heatmap: the renderer's bottom margin (column labels)
 * @property {number} [seriesCount] band charts: series slots (hidden series keep their slot)
 * @property {number} [marginLeft] band charts: the renderer's left margin from the outer width
 * @property {number} [marginRight] band charts: the renderer's right margin
 * @property {number} availableWidth `W`: the card's measured content width (grid column when in a grid)
 * @property {ChartSizeContext} [context]
 * @property {number} [availableHeight] expand layer only: the layer's usable inner height
 * @typedef {object} ChartPlotSize
 * @property {number} w logical plot width; `w < W` means the host is centred, never stretched
 * @property {number | null} h logical plot height; `null` keeps the renderer's own budget (horizontal bar)
 * @property {"below" | "aside"} legendPlacement
 * @property {number} legendWidth aside legend column width, else 0
 * @property {number} availableWidth the clamped `W` the decision used
 * @property {{ w: number, h: number }} [cell] heatmap only: the cell size the width and height derive from
 */

/** Widest category step for `S` series slots: `min(96 + 32 × (S − 1), 256)`. @param {number} seriesCount */
export function bandMaxStep(seriesCount) {
  const S = Math.max(1, Math.floor(Number(seriesCount) || 1));
  return Math.min(BAND_STEP_BASE + BAND_STEP_PER_SERIES * (S - 1), BAND_STEP_MAX);
}

/** The pie disc side for an available width and the context's cap. */
function pieSide(W, context, availableHeight) {
  const cap =
    context === "expand" && Number.isFinite(availableHeight) && availableHeight > 0
      ? Math.max(PIE_MIN_SIDE, Math.floor(availableHeight))
      : PIE_MAX_SIDE;
  return Math.max(PIE_MIN_SIDE, Math.min(cap, Math.round(W / PHI)));
}

/**
 * Decide the plot size for one chart (§4.6 policy table).
 * @param {ChartPlotSizeInput} input
 * @returns {ChartPlotSize}
 */
export function chartPlotSize(input) {
  const context = input.context === "expand" || input.context === "print" ? input.context : "card";
  const raw = Number(input.availableWidth);
  const W = Number.isFinite(raw) && raw > 0 ? Math.max(MIN_PLOT_WIDTH, Math.round(raw)) : 640;
  const kind = String(input.kind ?? "");
  if (kind === "pie") {
    const side = pieSide(W, context, input.availableHeight);
    const spare = W - side - PIE_LEGEND_GAP;
    const aside = spare >= PIE_LEGEND_MIN_WIDTH;
    return {
      w: side,
      h: side,
      legendPlacement: aside ? "aside" : "below",
      legendWidth: aside ? Math.min(PIE_LEGEND_MAX_WIDTH, spare) : 0,
      availableWidth: W,
    };
  }
  if (kind === "bar" && input.orientation === "horizontal") {
    return { w: W, h: null, legendPlacement: "below", legendWidth: 0, availableWidth: W };
  }
  if (kind === "heatmap") {
    const rows = Math.max(1, Math.floor(Number(input.rows) || 1));
    const cols = Math.max(1, Math.floor(Number(input.cols) || 1));
    const left = Number.isFinite(input.marginLeft) ? input.marginLeft : 0;
    const right = Number.isFinite(input.marginRight) ? input.marginRight : 0;
    const top = Number.isFinite(input.marginTop) ? input.marginTop : 0;
    const bottom = Number.isFinite(input.marginBottom) ? input.marginBottom : 0;
    // A row label is one line of text: no context may make a row shorter than that line (print used to go
    // down to 8 px and still drew a 12 px label on every row, so neighbours overprinted).
    const minRowH = Number.isFinite(input.minCellHeight) ? Math.max(0, Math.ceil(input.minCellHeight)) : 0;
    let cellW;
    let cellH;
    if (context === "print") {
      // Print is the only context that lifts the 20 px floor: every column fits the page width.
      const printW = Math.min(W, HEAT_PRINT_MAX_WIDTH);
      const innerW = Math.max(0, printW - left - right);
      cellW = Math.max(HEAT_PRINT_CELL_MIN_W, Math.floor(innerW / cols));
      cellH = Math.max(HEAT_PRINT_CELL_MIN_H, minRowH, Math.min(HEAT_CELL_MAX_H, Math.round(cellW * HEAT_CELL_H_RATIO)));
    } else {
      const innerW = Math.max(0, W - left - right);
      cellW = Math.max(HEAT_CELL_MIN_W, Math.min(HEAT_CELL_MAX_W, Math.floor(innerW / cols)));
      cellH = Math.max(HEAT_CELL_MIN_H, minRowH, Math.min(HEAT_CELL_MAX_H, Math.round(cellW * HEAT_CELL_H_RATIO)));
    }
    const w = Math.round(left + cols * cellW + right);
    const h = Math.round(top + rows * cellH + bottom);
    return { w, h, legendPlacement: "below", legendWidth: 0, availableWidth: W, cell: { w: cellW, h: cellH } };
  }
  if (kind === "bar" || kind === "boxplot") {
    const n = Math.max(1, Math.floor(Number(input.categories) || 1));
    const S = kind === "boxplot" ? 1 : Math.max(1, Math.floor(Number(input.seriesCount) || 1));
    const left = Number.isFinite(input.marginLeft) ? input.marginLeft : 0;
    const right = Number.isFinite(input.marginRight) ? input.marginRight : 0;
    const wanted = left + n * bandMaxStep(S) + right;
    const w = Math.max(MIN_PLOT_WIDTH, Math.min(W, Math.round(wanted)));
    return { w, h: DEFAULT_PLOT_HEIGHT, legendPlacement: "below", legendWidth: 0, availableWidth: W };
  }
  return { w: W, h: DEFAULT_PLOT_HEIGHT, legendPlacement: "below", legendWidth: 0, availableWidth: W };
}
